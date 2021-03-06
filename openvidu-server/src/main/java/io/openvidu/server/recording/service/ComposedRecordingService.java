/*
 * (C) Copyright 2017-2019 OpenVidu (https://openvidu.io/)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */

package io.openvidu.server.recording.service;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.CreateContainerCmd;
import com.github.dockerjava.api.command.CreateContainerResponse;
import com.github.dockerjava.api.command.ExecCreateCmdResponse;
import com.github.dockerjava.api.exception.ConflictException;
import com.github.dockerjava.api.exception.NotFoundException;
import com.github.dockerjava.api.model.Bind;
import com.github.dockerjava.api.model.Volume;
import com.github.dockerjava.core.DefaultDockerClientConfig;
import com.github.dockerjava.core.DockerClientBuilder;
import com.github.dockerjava.core.DockerClientConfig;
import com.github.dockerjava.core.command.ExecStartResultCallback;

import io.openvidu.client.OpenViduException;
import io.openvidu.client.OpenViduException.Code;
import io.openvidu.java.client.RecordingLayout;
import io.openvidu.java.client.RecordingProperties;
import io.openvidu.server.OpenViduServer;
import io.openvidu.server.config.OpenviduConfig;
import io.openvidu.server.core.Participant;
import io.openvidu.server.core.Session;
import io.openvidu.server.kurento.core.KurentoParticipant;
import io.openvidu.server.kurento.core.KurentoSession;
import io.openvidu.server.recording.CompositeWrapper;
import io.openvidu.server.recording.Recording;
import io.openvidu.server.recording.RecordingInfoUtils;

public class ComposedRecordingService extends RecordingService {

	private static final Logger log = LoggerFactory.getLogger(ComposedRecordingService.class);

	private Map<String, String> containers = new ConcurrentHashMap<>();
	private Map<String, String> sessionsContainers = new ConcurrentHashMap<>();
	private Map<String, CompositeWrapper> composites = new ConcurrentHashMap<>();

	DockerClient dockerClient;

	public ComposedRecordingService(RecordingManager recordingManager, OpenviduConfig openviduConfig) {
		super(recordingManager, openviduConfig);
		DockerClientConfig config = DefaultDockerClientConfig.createDefaultConfigBuilder().build();
		this.dockerClient = DockerClientBuilder.getInstance(config).build();
	}

	@Override
	public Recording startRecording(Session session, RecordingProperties properties) throws OpenViduException {

		PropertiesRecordingId updatePropertiesAndRecordingId = this.setFinalRecordingNameAndGetFreeRecordingId(session,
				properties);
		properties = updatePropertiesAndRecordingId.properties;
		String recordingId = updatePropertiesAndRecordingId.recordingId;

		// Instantiate and store recording object
		Recording recording = new Recording(session.getSessionId(), recordingId, properties);
		this.recordingManager.startingRecordings.put(recording.getId(), recording);

		if (properties.hasVideo()) {
			// Docker container used
			recording = this.startRecordingWithVideo(session, recording, properties);
		} else {
			// Kurento composite used
			recording = this.startRecordingAudioOnly(session, recording, properties);
		}

		// Update collections and return recording
		this.updateCollectionsAndSendNotifCauseRecordingStarted(session, recording);
		return recording;
	}

	@Override
	public Recording stopRecording(Session session, Recording recording, String reason) {
		if (recording.hasVideo()) {
			return this.stopRecordingWithVideo(session, recording, reason);
		} else {
			return this.stopRecordingAudioOnly(session, recording, reason);
		}
	}

	public void joinPublisherEndpointToComposite(Session session, String recordingId, Participant participant)
			throws OpenViduException {
		log.info("Joining single stream {} to Composite in session {}", participant.getPublisherStreamId(),
				session.getSessionId());
		
		KurentoParticipant kurentoParticipant = (KurentoParticipant) participant;
		CompositeWrapper compositeWrapper = this.composites.get(session.getSessionId());

		try {
			compositeWrapper.connectPublisherEndpoint(kurentoParticipant.getPublisher());
		} catch (OpenViduException e) {
			if (Code.RECORDING_START_ERROR_CODE.getValue() == e.getCodeValue()) {
				// First user publishing triggered RecorderEnpoint start, but it failed
				throw e;
			}
		}
	}

	public void removePublisherEndpointFromComposite(String sessionId, String streamId) {
		CompositeWrapper compositeWrapper = this.composites.get(sessionId);
		compositeWrapper.disconnectPublisherEndpoint(streamId);
	}

	private Recording startRecordingWithVideo(Session session, Recording recording, RecordingProperties properties)
			throws OpenViduException {
		List<String> envs = new ArrayList<>();

		String layoutUrl = this.getLayoutUrl(recording, this.getShortSessionId(session));

		envs.add("URL=" + layoutUrl);
		envs.add("ONLY_VIDEO=" + !properties.hasAudio());
		envs.add("RESOLUTION=" + properties.resolution());
		envs.add("FRAMERATE=30");
		envs.add("VIDEO_ID=" + recording.getId());
		envs.add("VIDEO_NAME=" + properties.name());
		envs.add("VIDEO_FORMAT=mp4");
		envs.add("RECORDING_JSON=" + recording.toJson().toString());

		log.info(recording.toJson().toString());
		log.info("Recorder connecting to url {}", layoutUrl);

		String containerId;
		try {
			containerId = this.runRecordingContainer(envs, "recording_" + recording.getId());
		} catch (Exception e) {
			this.cleanRecordingMaps(recording);
			throw this.failStartRecording(session, recording,
					"Couldn't initialize recording container. Error: " + e.getMessage());
		}

		this.sessionsContainers.put(session.getSessionId(), containerId);

		try {
			this.waitForVideoFileNotEmpty(recording);
		} catch (OpenViduException e) {
			this.cleanRecordingMaps(recording);
			throw this.failStartRecording(session, recording,
					"Couldn't initialize recording container. Error: " + e.getMessage());
		}

		return recording;
	}

	private Recording startRecordingAudioOnly(Session session, Recording recording, RecordingProperties properties)
			throws OpenViduException {

		CompositeWrapper compositeWrapper = new CompositeWrapper((KurentoSession) session,
				"file://" + this.openviduConfig.getOpenViduRecordingPath() + recording.getId() + "/" + properties.name()
						+ ".webm");
		this.composites.put(session.getSessionId(), compositeWrapper);

		for (Participant p : session.getParticipants()) {
			if (p.isStreaming()) {
				try {
					this.joinPublisherEndpointToComposite(session, recording.getId(), p);
				} catch (OpenViduException e) {
					log.error("Error waiting for RecorderEndpooint of Composite to start in session {}",
							session.getSessionId());
					throw this.failStartRecording(session, recording, e.getMessage());
				}
			}
		}

		this.generateRecordingMetadataFile(recording);

		return recording;
	}

	private Recording stopRecordingWithVideo(Session session, Recording recording, String reason) {

		String containerId = this.sessionsContainers.remove(recording.getSessionId());
		this.cleanRecordingMaps(recording);

		final String recordingId = recording.getId();

		if (session == null) {
			log.warn(
					"Existing recording {} does not have an active session associated. This usually means the recording"
							+ " layout did not join a recorded participant or the recording has been automatically"
							+ " stopped after last user left and timeout passed",
					recording.getId());
		}

		if (containerId == null) {

			// Session was closed while recording container was initializing
			// Wait until containerId is available and force its stop and removal
			new Thread(() -> {
				log.warn("Session closed while starting recording container");
				boolean containerClosed = false;
				String containerIdAux;
				int timeOut = 0;
				while (!containerClosed && (timeOut < 30)) {
					containerIdAux = this.sessionsContainers.remove(session.getSessionId());
					if (containerIdAux == null) {
						try {
							log.warn("Waiting for container to be launched...");
							timeOut++;
							Thread.sleep(500);
						} catch (InterruptedException e) {
							e.printStackTrace();
						}
					} else {
						log.warn("Removing container {} for closed session {}...", containerIdAux,
								session.getSessionId());
						dockerClient.stopContainerCmd(containerIdAux).exec();
						this.removeDockerContainer(containerIdAux);
						containerClosed = true;
						log.warn("Container {} for closed session {} succesfully stopped and removed", containerIdAux,
								session.getSessionId());
						log.warn("Deleting unusable files for recording {}", recordingId);
						if (HttpStatus.NO_CONTENT
								.equals(this.recordingManager.deleteRecordingFromHost(recordingId, true))) {
							log.warn("Files properly deleted");
						}
					}
				}
			}).start();

		} else {

			// Gracefully stop ffmpeg process
			ExecCreateCmdResponse execCreateCmdResponse = dockerClient.execCreateCmd(containerId).withAttachStdout(true)
					.withAttachStderr(true).withCmd("bash", "-c", "echo 'q' > stop").exec();
			try {
				dockerClient.execStartCmd(execCreateCmdResponse.getId()).exec(new ExecStartResultCallback())
						.awaitCompletion();
			} catch (InterruptedException e) {
				e.printStackTrace();
			}

			// Wait for the container to be gracefully self-stopped
			CountDownLatch latch = new CountDownLatch(1);
			WaitForContainerStoppedCallback callback = new WaitForContainerStoppedCallback(latch);
			dockerClient.waitContainerCmd(containerId).exec(callback);

			boolean stopped = false;
			try {
				stopped = latch.await(60, TimeUnit.SECONDS);
			} catch (InterruptedException e) {
				recording.setStatus(io.openvidu.java.client.Recording.Status.failed);
				failRecordingCompletion(containerId, new OpenViduException(Code.RECORDING_COMPLETION_ERROR_CODE,
						"The recording completion process has been unexpectedly interrupted"));
			}
			if (!stopped) {
				recording.setStatus(io.openvidu.java.client.Recording.Status.failed);
				failRecordingCompletion(containerId, new OpenViduException(Code.RECORDING_COMPLETION_ERROR_CODE,
						"The recording completion process couldn't finish in 60 seconds"));
			}

			// Remove container
			this.removeDockerContainer(containerId);

			// Update recording attributes reading from video report file
			try {
				RecordingInfoUtils infoUtils = new RecordingInfoUtils(
						this.openviduConfig.getOpenViduRecordingPath() + recordingId + "/" + recordingId + ".info");

				recording.setStatus(io.openvidu.java.client.Recording.Status.stopped);
				recording.setDuration(infoUtils.getDurationInSeconds());
				recording.setSize(infoUtils.getSizeInBytes());
				recording.setResolution(infoUtils.videoWidth() + "x" + infoUtils.videoHeight());
				recording.setHasAudio(infoUtils.hasAudio());
				recording.setHasVideo(infoUtils.hasVideo());

				infoUtils.deleteFilePath();

				recording = this.recordingManager.updateRecordingUrl(recording);

			} catch (IOException e) {
				recording.setStatus(io.openvidu.java.client.Recording.Status.failed);
				throw new OpenViduException(Code.RECORDING_REPORT_ERROR_CODE,
						"There was an error generating the metadata report file for the recording");
			}
			if (session != null && reason != null) {
				this.recordingManager.sessionHandler.sendRecordingStoppedNotification(session, recording, reason);
			}
		}
		return recording;
	}

	private Recording stopRecordingAudioOnly(Session session, Recording recording, String reason) {
		String sessionId;
		if (session == null) {
			log.warn(
					"Existing recording {} does not have an active session associated. This means the recording "
							+ "has been automatically stopped after last user left and {} seconds timeout passed",
					recording.getId(), this.openviduConfig.getOpenviduRecordingAutostopTimeout());
			sessionId = recording.getSessionId();
		} else {
			sessionId = session.getSessionId();
		}

		CompositeWrapper compositeWrapper = this.composites.remove(sessionId);

		final CountDownLatch stoppedCountDown = new CountDownLatch(1);
		compositeWrapper.stopCompositeRecording(stoppedCountDown);
		try {
			if (!stoppedCountDown.await(5, TimeUnit.SECONDS)) {
				recording.setStatus(io.openvidu.java.client.Recording.Status.failed);
				log.error("Error waiting for RecorderEndpoint of Composite to stop in session {}",
						recording.getSessionId());
			}
		} catch (InterruptedException e) {
			recording.setStatus(io.openvidu.java.client.Recording.Status.failed);
			log.error("Exception while waiting for state change", e);
		}

		compositeWrapper.disconnectAllPublisherEndpoints();

		this.cleanRecordingMaps(recording);

		String filesPath = this.openviduConfig.getOpenViduRecordingPath() + recording.getId() + "/";
		File videoFile = new File(filesPath + recording.getName() + ".webm");
		long finalSize = videoFile.length();
		long finalDuration = compositeWrapper.getDuration();

		this.sealRecordingMetadataFile(recording, finalSize, finalDuration,
				filesPath + RecordingManager.RECORDING_ENTITY_FILE + recording.getId());

		if (reason != null && session != null) {
			this.recordingManager.sessionHandler.sendRecordingStoppedNotification(session, recording, reason);
		}

		return recording;
	}

	private String runRecordingContainer(List<String> envs, String containerName) throws Exception {
		Volume volume1 = new Volume("/recordings");
		CreateContainerCmd cmd = dockerClient
				.createContainerCmd(RecordingManager.IMAGE_NAME + ":" + RecordingManager.IMAGE_TAG)
				.withName(containerName).withEnv(envs).withNetworkMode("host").withVolumes(volume1)
				.withBinds(new Bind(openviduConfig.getOpenViduRecordingPath(), volume1));
		CreateContainerResponse container = null;
		try {
			container = cmd.exec();
			dockerClient.startContainerCmd(container.getId()).exec();
			containers.put(container.getId(), containerName);
			log.info("Container ID: {}", container.getId());
			return container.getId();
		} catch (ConflictException e) {
			log.error(
					"The container name {} is already in use. Probably caused by a session with unique publisher re-publishing a stream",
					containerName);
			throw e;
		} catch (NotFoundException e) {
			log.error("Docker image {} couldn't be found in docker host",
					RecordingManager.IMAGE_NAME + ":" + RecordingManager.IMAGE_TAG);
			throw e;
		}
	}

	private void removeDockerContainer(String containerId) {
		dockerClient.removeContainerCmd(containerId).exec();
		containers.remove(containerId);
	}

	private void stopDockerContainer(String containerId) {
		dockerClient.stopContainerCmd(containerId).exec();
	}

	private void waitForVideoFileNotEmpty(Recording recording) throws OpenViduException {
		boolean isPresent = false;
		int i = 1;
		int timeout = 150; // Wait for 150*150 = 22500 = 22.5 seconds
		while (!isPresent && timeout <= 150) {
			try {
				Thread.sleep(150);
				timeout++;
				File f = new File(this.openviduConfig.getOpenViduRecordingPath() + recording.getId() + "/"
						+ recording.getName() + ".mp4");
				isPresent = ((f.isFile()) && (f.length() > 0));
			} catch (InterruptedException e) {
				e.printStackTrace();
			}
		}
		if (i == timeout) {
			log.error("Recorder container failed generating video file (is empty) for session {}",
					recording.getSessionId());
			throw new OpenViduException(Code.RECORDING_START_ERROR_CODE,
					"Recorder container failed generating video file (is empty)");
		}
	}

	private void failRecordingCompletion(String containerId, OpenViduException e) throws OpenViduException {
		this.stopDockerContainer(containerId);
		this.removeDockerContainer(containerId);
		throw e;
	}

	private String getLayoutUrl(Recording recording, String shortSessionId) {
		String secret = openviduConfig.getOpenViduSecret();
		String location = OpenViduServer.wsUrl.replaceFirst("wss://", "");
		String layout, finalUrl;

		if (RecordingLayout.CUSTOM.equals(recording.getRecordingLayout())) {
			layout = recording.getCustomLayout();
			if (!layout.isEmpty()) {
				layout = layout.startsWith("/") ? layout : ("/" + layout);
				layout = layout.endsWith("/") ? layout.substring(0, layout.length() - 1) : layout;
			}
			layout += "/index.html";
			finalUrl = "https://OPENVIDUAPP:" + secret + "@" + location + "/layouts/custom" + layout + "?sessionId="
					+ shortSessionId + "&secret=" + secret;
		} else {
			layout = recording.getRecordingLayout().name().toLowerCase().replaceAll("_", "-");
			finalUrl = "https://OPENVIDUAPP:" + secret + "@" + location + "/#/layout-" + layout + "/" + shortSessionId
					+ "/" + secret;
		}

		return finalUrl;
	}

}

/*
 * Copyright 2013-2020 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.springframework.cloud.contract.stubrunner.provider.wiremock;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.common.JsonException;
import com.github.tomakehurst.wiremock.common.Notifier;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.github.tomakehurst.wiremock.extension.Extension;
import com.github.tomakehurst.wiremock.security.ClientAuthenticator;
import com.github.tomakehurst.wiremock.security.NoClientAuthenticator;
import com.github.tomakehurst.wiremock.stubbing.StubMapping;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.cloud.contract.stubrunner.HttpServerStub;
import org.springframework.cloud.contract.stubrunner.HttpServerStubConfiguration;
import org.springframework.cloud.contract.stubrunner.HttpServerStubConfigurer;
import org.springframework.cloud.contract.verifier.dsl.wiremock.HandlebarsEscapeHelperExtension;
import org.springframework.cloud.contract.verifier.dsl.wiremock.HandlebarsJsonHelperExtension;
import org.springframework.cloud.contract.verifier.dsl.wiremock.SpringCloudContractRequestMatcher;
import org.springframework.cloud.contract.verifier.dsl.wiremock.WireMockExtensions;
import org.springframework.cloud.contract.wiremock.WireMockSpring;
import org.springframework.core.io.support.SpringFactoriesLoader;
import org.springframework.util.ClassUtils;
import org.springframework.util.StreamUtils;
import org.springframework.util.StringUtils;

/**
 * Abstraction over WireMock as a HTTP Server Stub.
 *
 * @author Marcin Grzejszczak
 * @since 1.1.0
 */
public class WireMockHttpServerStub implements HttpServerStub {

	static final Map<WireMockHttpServerStub, PortAndMappings> SERVERS = new ConcurrentHashMap<>();

	private static final Log log = LogFactory.getLog(WireMockHttpServerStub.class);

	private static final int INVALID_PORT = -1;

	private WireMockServer wireMockServer;

	private boolean https = false;

	private WireMockConfiguration wireMockConfiguration;

	private WireMockConfiguration config() {
		if (ClassUtils.isPresent("org.springframework.cloud.contract.wiremock.WireMockSpring", null)) {
			return WireMockSpring.options().extensions(responseTransformers());
		}
		return new WireMockConfiguration().extensions(responseTransformers());
	}

	private Extension[] responseTransformers() {
		List<WireMockExtensions> wireMockExtensions = SpringFactoriesLoader.loadFactories(WireMockExtensions.class,
				null);
		List<Extension> extensions = new ArrayList<>();
		if (!wireMockExtensions.isEmpty()) {
			for (WireMockExtensions wireMockExtension : wireMockExtensions) {
				extensions.addAll(wireMockExtension.extensions());
			}
		}
		else {
			extensions.addAll(Arrays.asList(new HandlebarsEscapeHelperExtension(), new HandlebarsJsonHelperExtension(),
					new SpringCloudContractRequestMatcher()));
		}
		return extensions.toArray(new Extension[extensions.size()]);
	}

	@Override
	public int port() {
		return isRunning() ? (this.https ? this.wireMockServer.httpsPort() : this.wireMockServer.port()) : INVALID_PORT;
	}

	@Override
	public boolean isRunning() {
		return this.wireMockServer != null && this.wireMockServer.isRunning();
	}

	@Override
	public HttpServerStub start(HttpServerStubConfiguration configuration) {
		if (isRunning()) {
			if (log.isTraceEnabled()) {
				log.trace("The server is already running at port [" + port() + "]");
			}
			return this;
		}
		int port = configuration.port;
		WireMockConfiguration wireMockConfiguration = config().port(port).notifier(new Slf4jNotifier(true));
		if (configuration.configurer.isAccepted(wireMockConfiguration)) {
			@SuppressWarnings("unchecked")
			HttpServerStubConfigurer<WireMockConfiguration> configurer = configuration.configurer;
			wireMockConfiguration = configurer.configure(wireMockConfiguration, configuration);
		}
		this.wireMockConfiguration = wireMockConfiguration;
		this.https = wireMockConfiguration.httpsSettings().enabled();
		port = this.https ? wireMockConfiguration.httpsSettings().port() : wireMockConfiguration.portNumber();
		this.wireMockServer = new WireMockServer(wireMockConfiguration);
		this.wireMockServer.start();
		if (log.isDebugEnabled()) {
			log.debug("For " + configuration.toColonSeparatedDependencyNotation() + " Started WireMock at ["
					+ (this.https ? "https" : "http") + "] port [" + port + "]");
		}
		cacheStubServer(configuration.randomPort, port);
		return this;
	}

	@Override
	public int httpsPort() {
		return this.https ? port() : INVALID_PORT;
	}

	@Override
	public HttpServerStub reset() {
		this.wireMockServer.resetAll();
		return this;
	}

	private void cacheStubServer(boolean random, int port) {
		SERVERS.put(this, new PortAndMappings(random, port, new ArrayList<>()));
	}

	@Override
	public HttpServerStub stop() {
		if (!isRunning()) {
			if (log.isTraceEnabled()) {
				log.trace("Trying to stop a non started server!");
			}
			return this;
		}
		this.wireMockServer.stop();
		return this;
	}

	@Override
	public HttpServerStub registerMappings(Collection<File> stubFiles) {
		if (!isRunning()) {
			throw new IllegalStateException("Server not started!");
		}
		registerStubMappings(stubFiles);
		return this;
	}

	@Override
	public String registeredMappings() {
		Collection<String> mappings = new ArrayList<>();
		for (StubMapping stubMapping : this.wireMockServer.getStubMappings()) {
			mappings.add(stubMapping.toString());
		}
		return jsonArrayOfMappings(mappings);
	}

	private String jsonArrayOfMappings(Collection<String> mappings) {
		return "[" + StringUtils.collectionToDelimitedString(mappings, ",\n") + "]";
	}

	@Override
	public boolean isAccepted(File file) {
		return file.getName().endsWith(".json") && validMapping(file);
	}

	private boolean validMapping(File file) {
		try {
			getMapping(file);
			return true;
		}
		catch (IllegalStateException e) {
			return false;
		}
	}

	StubMapping getMapping(File file) {
		try (InputStream stream = Files.newInputStream(file.toPath())) {
			return StubMapping.buildFrom(StreamUtils.copyToString(stream, StandardCharsets.UTF_8));
		}
		catch (IOException | JsonException e) {
			throw new IllegalStateException("Cannot read file", e);
		}
	}

	private void registerStubMappings(Collection<File> stubFiles) {
		WireMock wireMock = wireMock();
		registerDefaultHealthChecks(wireMock);
		registerStubs(stubFiles, wireMock);
	}

	private WireMock wireMock() {
		String scheme = this.https ? "https" : "http";
		String host = "localhost";
		int port = port();
		String urlPathPrefix = "";
		String hostHeader = "";
		String proxyHost = this.wireMockConfiguration.proxyHostHeader();
		int proxyPort = this.wireMockConfiguration.proxyVia().port();
		ClientAuthenticator authenticator = NoClientAuthenticator.noClientAuthenticator();
		return new WireMock(scheme, host, port, urlPathPrefix, hostHeader, proxyHost, proxyPort, authenticator);
	}

	private void registerDefaultHealthChecks(WireMock wireMock) {
		registerHealthCheck(wireMock, "/ping");
		registerHealthCheck(wireMock, "/health");
	}

	private void registerStubs(Collection<File> sortedMappings, WireMock wireMock) {
		List<StubMapping> stubMappings = new ArrayList<>();
		for (File mappingDescriptor : sortedMappings) {
			try {
				stubMappings.add(registerDescriptor(wireMock, mappingDescriptor));
				if (log.isDebugEnabled()) {
					log.debug("Registered stub mappings from [" + mappingDescriptor + "]");
				}
			}
			catch (Exception e) {
				if (log.isDebugEnabled()) {
					log.debug("Failed to register the stub mapping [" + mappingDescriptor + "]", e);
				}
			}
		}
		PortAndMappings portAndMappings = SERVERS.get(this);
		SERVERS.put(this, new PortAndMappings(portAndMappings.random, portAndMappings.port, stubMappings));
	}

	private StubMapping registerDescriptor(WireMock wireMock, File mappingDescriptor) {
		StubMapping mapping = getMapping(mappingDescriptor);
		wireMock.register(mapping);
		return mapping;
	}

	private void registerHealthCheck(WireMock wireMock, String url) {
		registerHealthCheck(wireMock, url, "OK");
	}

	private void registerHealthCheck(WireMock wireMock, String url, String body) {
		wireMock.register(
				WireMock.get(WireMock.urlEqualTo(url)).willReturn(WireMock.aResponse().withBody(body).withStatus(200)));
	}

	static class Slf4jNotifier implements Notifier {

		private static final Logger log = LoggerFactory.getLogger("WireMock");

		private final boolean verbose;

		Slf4jNotifier(boolean verbose) {
			this.verbose = verbose;
		}

		@Override
		public void info(String message) {
			if (verbose) {
				log.info(message);
			}
		}

		@Override
		public void error(String message) {
			log.error(message);
		}

		@Override
		public void error(String message, Throwable t) {
			log.error(message, t);
		}

	}

}

class PortAndMappings {

	final boolean random;

	final Integer port;

	final List<StubMapping> mappings;

	PortAndMappings(boolean random, Integer port, List<StubMapping> mappings) {
		this.random = random;
		this.port = port;
		this.mappings = mappings;
	}

	@Override
	public String toString() {
		return "PortAndMappings{" + "random=" + this.random + ", port=" + this.port + ", mappings=" + this.mappings
				+ '}';
	}

}

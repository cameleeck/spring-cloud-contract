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

package org.springframework.cloud.contract.stubrunner.provider.wiremock

import com.github.tomakehurst.wiremock.common.FileSource
import com.github.tomakehurst.wiremock.extension.Extension
import com.github.tomakehurst.wiremock.extension.Parameters
import com.github.tomakehurst.wiremock.extension.ResponseTransformer
import com.github.tomakehurst.wiremock.http.HttpHeader
import com.github.tomakehurst.wiremock.http.HttpHeaders
import com.github.tomakehurst.wiremock.http.Request
import com.github.tomakehurst.wiremock.http.Response
import groovy.transform.CompileStatic

import org.springframework.cloud.contract.verifier.dsl.wiremock.WireMockExtensions

/**
 * Extension that registers the default response transformer and a custom one too
 */
@CompileStatic
class TestWireMockExtensions implements WireMockExtensions {
	@Override
	List<Extension> extensions() {
		return [new CustomExtension()] as List<Extension>
	}
}

@CompileStatic
class CustomExtension extends ResponseTransformer {

	/**
	 * We expect the mapping to contain "foo-transformer" in the list
	 * of "response-transformers" in the stub mapping
	 */
	@Override
	String getName() {
		return "foo-transformer"
	}

	/**
	 * Transformer adds a X-My-Header with value "surprise!" to the response
	 */
	@Override
	Response transform(Request request, Response response, FileSource files, Parameters parameters) {
		HttpHeaders headers = response.headers + new HttpHeader("X-My-Header", "surprise!")
		return Response.response()
					   .status(response.status)
					   .statusMessage(response.statusMessage)
					   .body(response.body)
					   .headers(headers)
					   .configured(response.wasConfigured())
					   .fault(response.fault)
					   .incrementInitialDelay(response.initialDelay)
					   .chunkedDribbleDelay(response.chunkedDribbleDelay)
					   .fromProxy(response.fromProxy)
					   .build()
	}

	/**
	 * We don't want this extension to be applied to every single mapping.
	 * We just want this to take place when a mapping explicitly expresses that in the
	 * "response-transformers" section
	 */
	@Override
	boolean applyGlobally() {
		return false
	}
}

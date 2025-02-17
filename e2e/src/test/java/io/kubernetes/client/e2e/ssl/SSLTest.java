/*
Copyright 2024 The Kubernetes Authors.
Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at
http://www.apache.org/licenses/LICENSE-2.0
Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
*/
package io.kubernetes.client.e2e.ssl;

import io.kubernetes.client.e2e.util.TestUtils;
import io.kubernetes.client.informer.SharedIndexInformer;
import io.kubernetes.client.informer.SharedInformerFactory;
import io.kubernetes.client.informer.cache.Lister;
import io.kubernetes.client.openapi.ApiClient;
import io.kubernetes.client.openapi.models.V1Namespace;
import io.kubernetes.client.openapi.models.V1NamespaceList;
import io.kubernetes.client.util.ClientBuilder;
import io.kubernetes.client.util.KubeConfig;
import io.kubernetes.client.util.generic.GenericKubernetesApi;
import nl.altindag.log.LogCaptor;
import nl.altindag.log.model.LogEvent;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.util.Base64;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

class SSLTest {

    @Test
    void buildClientWithReloadableSslConfiguration() throws Exception {
        LogCaptor logCaptorForJdkSecurityEvents = LogCaptor.forName("jdk.event.security");
        logCaptorForJdkSecurityEvents.disableLogs();

        LogCaptor logCaptor = LogCaptor.forRoot();

        ApiClient apiClient = ClientBuilder.defaultClient();
        apiClient.setSslCaCert(null);

        SharedInformerFactory informerFactory = new SharedInformerFactory(apiClient);
        GenericKubernetesApi<V1Namespace, V1NamespaceList> api = new GenericKubernetesApi<>(V1Namespace.class, V1NamespaceList.class, "", "v1", "namespaces", apiClient);
        SharedIndexInformer<V1Namespace> nsInformer = informerFactory.sharedIndexInformerFor(api, V1Namespace.class, 0);
        Lister<V1Namespace> nsLister = new Lister<>(nsInformer.getIndexer());

        try {
            informerFactory.startAllRegisteredInformers();

            await().untilAsserted(() -> {
                nsInformer.hasSynced();
                Optional<Throwable> certificateNotFoundException = getCertificateNotFoundException(logCaptor);
                assertThat(certificateNotFoundException).isPresent();
            });

            logCaptor.clearLogs();

            Optional<byte[]> certificateAuthorityData = getCertificateAuthorityDataFromKubeConfig();
            assertThat(certificateAuthorityData).isPresent();
            apiClient.setSslCaCert(new ByteArrayInputStream(certificateAuthorityData.get()));

            await().untilAsserted(() -> {
                assertThat(nsInformer.hasSynced()).isTrue();
                assertThat(nsLister.list()).isNotEmpty();
                Optional<Throwable> certificateNotFoundException = getCertificateNotFoundException(logCaptor);
                assertThat(certificateNotFoundException).isEmpty();
            });
        } finally {
            informerFactory.stopAllRegisteredInformers(true);
            logCaptorForJdkSecurityEvents.resetLogLevel();
            logCaptor.close();
        }
    }

    private static Optional<byte[]> getCertificateAuthorityDataFromKubeConfig() {
        return TestUtils.getKubeConfig()
                .map(KubeConfig::getCertificateAuthorityData)
                .map(base64EncodedCertificateAuthority -> Base64.getDecoder().decode(base64EncodedCertificateAuthority));
    }

    private static Optional<Throwable> getCertificateNotFoundException(LogCaptor logCaptor) {
        return logCaptor.getLogEvents().stream()
                .map(LogEvent::getThrowable)
                .filter(Optional::isPresent)
                .map(Optional::get)
                .filter(exception -> exception.getMessage().contains("unable to find valid certification path to requested target"))
                .findAny();
    }

}

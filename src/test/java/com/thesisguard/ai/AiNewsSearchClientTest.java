package com.thesisguard.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit test for the SSRF allow-rule. Uses literal IPv4 addresses so no DNS lookup is needed,
 * keeping the assertions deterministic and offline.
 */
class AiNewsSearchClientTest {

    private final AiNewsSearchClient client = new AiNewsSearchClient(
            new OpenRouterProperties(null, null, null, null, null),
            new ObjectMapper(), true, 24, 25, true, "exa");

    @Test
    void rejectsInternalAndNonHttpUrls() {
        assertThat(client.isPublicHttpUrl("http://127.0.0.1/")).isFalse();          // loopback
        assertThat(client.isPublicHttpUrl("http://169.254.169.254/latest")).isFalse(); // cloud metadata (link-local)
        assertThat(client.isPublicHttpUrl("http://10.1.2.3/")).isFalse();           // RFC1918
        assertThat(client.isPublicHttpUrl("http://192.168.0.1/")).isFalse();        // RFC1918
        assertThat(client.isPublicHttpUrl("http://172.16.5.5/")).isFalse();         // RFC1918
        assertThat(client.isPublicHttpUrl("http://100.64.0.1/")).isFalse();         // CGNAT 100.64/10
        assertThat(client.isPublicHttpUrl("http://224.0.0.1/")).isFalse();          // multicast
        assertThat(client.isPublicHttpUrl("ftp://8.8.8.8/")).isFalse();             // non-http scheme
        assertThat(client.isPublicHttpUrl("file:///etc/passwd")).isFalse();         // non-http scheme
        assertThat(client.isPublicHttpUrl("not a url")).isFalse();                  // no host
    }

    @Test
    void allowsPublicHttpsUrls() {
        assertThat(client.isPublicHttpUrl("https://8.8.8.8/article")).isTrue();
        assertThat(client.isPublicHttpUrl("https://1.1.1.1/news")).isTrue();
    }
}

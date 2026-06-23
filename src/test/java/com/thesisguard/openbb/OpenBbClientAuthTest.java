package com.thesisguard.openbb;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.headerDoesNotExist;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.http.HttpMethod.GET;

class OpenBbClientAuthTest {

    private static final String PROFILE_JSON = "{\"results\":[{\"symbol\":\"NVDA\"}]}";

    @Test
    void sendsApiKeyHeaderWhenConfigured() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        OpenBbClient client = new OpenBbClient(new OpenBbProperties("http://openbb.test", "secret-key-123"), builder);

        server.expect(requestTo(startsWith("http://openbb.test/api/v1/equity/profile")))
                .andExpect(method(GET))
                .andExpect(header("x-api-key", "secret-key-123"))
                .andRespond(withSuccess(PROFILE_JSON, MediaType.APPLICATION_JSON));

        client.fetchProfile("NVDA");
        server.verify();
    }

    @Test
    void omitsApiKeyHeaderWhenNotConfigured() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        OpenBbClient client = new OpenBbClient(new OpenBbProperties("http://openbb.test", null), builder);

        server.expect(requestTo(startsWith("http://openbb.test/api/v1/equity/profile")))
                .andExpect(headerDoesNotExist("x-api-key"))
                .andRespond(withSuccess(PROFILE_JSON, MediaType.APPLICATION_JSON));

        client.fetchProfile("NVDA");
        server.verify();
    }

    @Test
    void omitsApiKeyHeaderWhenBlank() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        OpenBbClient client = new OpenBbClient(new OpenBbProperties("http://openbb.test", "  "), builder);

        server.expect(requestTo(startsWith("http://openbb.test/api/v1/equity/profile")))
                .andExpect(headerDoesNotExist("x-api-key"))
                .andRespond(withSuccess(PROFILE_JSON, MediaType.APPLICATION_JSON));

        client.fetchProfile("NVDA");
        server.verify();
    }
}

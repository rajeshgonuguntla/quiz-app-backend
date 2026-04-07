package com.codapt.quizapp.service.impl;

import org.springframework.stereotype.Service;
import java.net.*;
import java.net.http.*;

@Service
public class ProxyServiceImpl {

    private final String username = System.getenv("DECODO_USERNAME");
    private final String password = System.getenv("DECODO_PASSWORD");

    public String getProxy() throws Exception {
        ProxySelector proxySelector = ProxySelector.of(
                new InetSocketAddress("gate.decodo.com", 10001)
        );

        Authenticator authenticator = new Authenticator() {
            @Override
            protected PasswordAuthentication getPasswordAuthentication() {
                return new PasswordAuthentication(username, password.toCharArray());
            }
        };

        HttpClient client = HttpClient.newBuilder()
                .proxy(proxySelector)
                .authenticator(authenticator)
                .build();

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("https://ip.decodo.com/json"))
                .GET()
                .build();

        HttpResponse<String> response = client.send(
                request, HttpResponse.BodyHandlers.ofString()
        );
        System.out.println("Proxy IP: " + response.body());
        return response.body();
    }
}

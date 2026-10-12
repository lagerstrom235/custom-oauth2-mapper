/**
 * Copyright © 2016-2018 ThingsBoard, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
// Modified in the sh.lagerstrom fork: package namespace and project adaptations.
package sh.lagerstrom.mapper.controller;

import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.StreamSupport;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import sh.lagerstrom.mapper.PEOAuth2User;
import sh.lagerstrom.mapper.config.MappingProperties;
import sh.lagerstrom.mapper.config.MappingProperties.Rule;
import org.thingsboard.server.common.data.id.CustomerId;
import org.thingsboard.server.common.data.id.TenantId;
import org.thingsboard.server.dao.oauth2.OAuth2User;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

@Slf4j
@RestController
@RequestMapping(path = "/oauth2")
public class CustomOAuth2MapperController {
    private final MappingProperties mapping;

    public CustomOAuth2MapperController(MappingProperties mapping) {
        this.mapping = mapping;
    }

    @PostMapping("/mapper")
    public OAuth2User map(@RequestBody String body) {
        log.debug("Starting OAuth2 mapping: professionalEdition={}", mapping.isProfessionalEdition());
        log.debug("Custom OAuth2 mapper request body: {}", body);
        final JsonNode user;

        try {
            user = (new ObjectMapper()).readTree(body);
        } catch (JsonProcessingException e) {
            throw badRequest("Expected valid JSON");
        }

        if (!user.isObject()) {
            throw badRequest("Expected a JSON object");
        }
        OAuth2User result = mapping.isProfessionalEdition() ? new PEOAuth2User() : new OAuth2User();
        String email = resolve(mapping.getEmail(), user, "email");
        result.setEmail(email);
        result.setFirstName(resolve(mapping.getFirstName(), user, "first-name"));
        result.setLastName(resolve(mapping.getLastName(), user, "last-name"));

        if (mapping.getTenantId() != null) {
            result.setTenantId(TenantId.fromUUID(resolveId(mapping.getTenantId(), user, "tenant-id")));
        } else if (mapping.getTenantName() != null) {
            result.setTenantName(resolve(mapping.getTenantName(), user, "tenant-name"));
        } else {
            log.debug("Resolving tenant name from email domain");
            result.setTenantName(email.substring(email.indexOf('@') + 1));
        }

        if (mapping.getCustomerId() != null) {
            result.setCustomerId(new CustomerId(resolveId(mapping.getCustomerId(), user, "customer-id")));
        } else if (mapping.getCustomerName() != null) {
            result.setCustomerName(resolve(mapping.getCustomerName(), user, "customer-name"));
        }
        if (result instanceof PEOAuth2User peUser) {
            if (mapping.getParentCustomerId() != null) {
                peUser.setParentCustomerId(new CustomerId(resolveId(mapping.getParentCustomerId(), user, "parent-customer-id")));
            } else if (mapping.getParentCustomerName() != null) {
                peUser.setParentCustomerName(resolve(mapping.getParentCustomerName(), user, "parent-customer-name"));
            }
            if (!mapping.getUserGroups().isEmpty()) {
                List<String> groups = new ArrayList<>();
                for (Rule rule : mapping.getUserGroups()) {
                    if (rule.getAttribute() != null && user.at(rule.getAttribute()).isArray()) {
                        for (JsonNode group : user.at(rule.getAttribute())) {
                            groups.add(rule.getPrefix() + text(group, "user-groups"));
                        }
                    } else {
                        groups.add(resolve(rule, user, "user-groups"));
                    }
                }
                peUser.setUserGroups(groups);
                log.debug("Resolved OAuth2 user groups: count={}", groups.size());
            }
        }

        if (System.getenv("CUSTOM_MAPPER_TB_API_URL") != null && System.getenv("CUSTOM_MAPPER_TB_API_URL").length() > 0) {
            HttpClient httpClient = HttpClient.newHttpClient();
            String tenantIdOrName = result.getTenantId() != null
                ? result.getTenantId().toString()
                : result.getTenantName();

            String encodedSearch = URLEncoder.encode(
                    tenantIdOrName, StandardCharsets.UTF_8
            );

            HttpRequest httpRequest = HttpRequest.newBuilder()
                .uri(URI.create(String.format(
                        "%s/api/tenants?pageSize=%s&page=0&textSearch=%s",
                        System.getenv("CUSTOM_MAPPER_TB_API_URL"),
                        Integer.MAX_VALUE,
                        encodedSearch
                )))
                .header("X-Authorization", String.format("ApiKey %s", System.getenv("CUSTOM_MAPPER_TB_API_KEY")))
                .build();

            final JsonNode tenants;
            log.debug("Starting ThingsBoard tenant lookup: searchBy={}",
                    result.getTenantId() != null ? "id" : "name");
            try {
                HttpResponse<String> httpResponse = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString());
                logLookupStatus("tenant", httpResponse.statusCode());
                tenants = (new ObjectMapper()).readTree(httpResponse.body());
            } catch (IOException | InterruptedException e) {
                log.debug("ThingsBoard tenant lookup failed: exceptionType={}", e.getClass().getSimpleName());
                throw badRequest("Expected valid JSON");
            }

            JsonNode tenant;
            if (tenants.get("totalElements").asInt() > 0) {
                tenant = StreamSupport.stream(tenants.get("data").spliterator(), false)
                    .filter(node -> 
                        (result.getTenantId() != null && node.path("id").path("id").asText().equals(result.getTenantId().toString())) ||
                        (result.getTenantName() != null && node.path("name").asText().equals(result.getTenantName()))
                    ).findFirst().orElse(null);
            } else {
                tenant = null;
            }

            String tenantId = tenant != null
                    ? tenant.path("id").path("id").asText()
                    : null;
            log.debug("Completed ThingsBoard tenant lookup: totalElements={}, exactMatch={}",
                    tenants.path("totalElements").asInt(), tenant != null);
        } else {
            log.debug("Skipping ThingsBoard tenant lookup: CUSTOM_MAPPER_TB_API_URL is not configured");
        }

        log.debug("Completed OAuth2 mapping: tenantIdSet={}, tenantNameSet={}, customerIdSet={}, customerNameSet={}",
                result.getTenantId() != null, result.getTenantName() != null,
                result.getCustomerId() != null, result.getCustomerName() != null);
        return result;
    }

    private String resolve(Rule rule, JsonNode user, String field) {
        log.debug("Resolving OAuth2 mapping field: field={}, source={}",
                field, rule.getValue() != null ? "configured value" : "user attribute");
        return rule.getPrefix() + (rule.getValue() != null
                ? rule.getValue() : text(user.at(rule.getAttribute()), field));
    }

    private String text(JsonNode node, String field) {
        if (!node.isTextual() || node.asText().isBlank()) {
            throw badRequest("Missing or invalid string attribute for " + field);
        }
        return node.asText();
    }

    private UUID resolveId(Rule rule, JsonNode user, String field) {
        try {
            return MappingProperties.parseId(resolve(rule, user, field));
        } catch (IllegalArgumentException e) {
            throw badRequest("Invalid UUID for " + field);
        }
    }

    private ResponseStatusException badRequest(String message) {
        log.warn("OAuth2 mapping rejected: {}", message);
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }

    private void logLookupStatus(String lookup, int statusCode) {
        if (statusCode < 200 || statusCode >= 300) {
            log.warn("ThingsBoard lookup returned unsuccessful HTTP status: lookup={}, status={}", lookup, statusCode);
        } else {
            log.debug("ThingsBoard lookup HTTP response: lookup={}, status={}", lookup, statusCode);
        }
    }

    private Boolean userInTenant(String tenantId, String email) {
        log.debug("Starting ThingsBoard tenant user lookup");
        String encodedSearch = URLEncoder.encode(
                email, StandardCharsets.UTF_8
        );

        HttpRequest httpRequest = HttpRequest.newBuilder()
            .uri(URI.create(String.format(
                    "%s/api/tenant/%s/users?pageSize=%s&page=0&textSearch=%s",
                    System.getenv("CUSTOM_MAPPER_TB_API_URL"),
                    tenantId,
                    Integer.MAX_VALUE,
                    encodedSearch
            )))
            .header("X-Authorization", String.format("ApiKey %s", System.getenv("CUSTOM_MAPPER_TB_API_KEY")))
            .build();

        HttpClient httpClient = HttpClient.newHttpClient();
        final JsonNode response;
        try {
            HttpResponse<String> httpResponse = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString());
            logLookupStatus("tenant user", httpResponse.statusCode());
            response = (new ObjectMapper()).readTree(httpResponse.body());
        } catch (IOException | InterruptedException e) {
            log.warn("ThingsBoard tenant user lookup failed: exceptionType={}", e.getClass().getSimpleName());
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        }


        JsonNode user;
        if (response.get("totalElements").asInt() > 0) {
            user = StreamSupport.stream(response.get("data").spliterator(), false)
                .filter(node -> 
                    node.path("email").asText().equals(email)
                ).findFirst().orElse(null);
        } else {
            user = null;
        }

        log.debug("Completed ThingsBoard tenant user lookup: totalElements={}, exactMatch={}",
                response.path("totalElements").asInt(), user != null);
        if (user != null) {
            return true;
        }

        return false;

    }
}

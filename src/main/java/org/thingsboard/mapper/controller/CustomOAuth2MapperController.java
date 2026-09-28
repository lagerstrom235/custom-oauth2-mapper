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
package org.thingsboard.mapper.controller;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.thingsboard.mapper.PEOAuth2User;
import org.thingsboard.mapper.config.MappingProperties;
import org.thingsboard.mapper.config.MappingProperties.Rule;
import org.thingsboard.server.common.data.id.CustomerId;
import org.thingsboard.server.common.data.id.TenantId;
import org.thingsboard.server.dao.oauth2.OAuth2User;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping(path = "/oauth2")
public class CustomOAuth2MapperController {
    private final MappingProperties mapping;

    public CustomOAuth2MapperController(MappingProperties mapping) {
        this.mapping = mapping;
    }

    @PostMapping("/mapper")
    public OAuth2User map(@RequestBody JsonNode user) {
        if (!user.isObject()) {
            throw badRequest("Expected a JSON object");
        }
        OAuth2User result = mapping.isProfessionalEdition() ? new PEOAuth2User() : new OAuth2User();
        String email = resolve(mapping.getEmail(), user, "email");
        result.setEmail(email);
        result.setFirstName(resolve(mapping.getFirstName(), user, "first-name"));
        result.setLastName(resolve(mapping.getLastName(), user, "last-name"));

        if (mapping.getTenantId() != null) {
            result.setTenantId(new TenantId(resolveId(mapping.getTenantId(), user, "tenant-id")));
        } else if (mapping.getTenantName() != null) {
            result.setTenantName(resolve(mapping.getTenantName(), user, "tenant-name"));
        } else {
            result.setTenantName("Tenant " + email.substring(email.indexOf('@') + 1));
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
            }
        }
        return result;
    }

    private String resolve(Rule rule, JsonNode user, String field) {
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
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }
}

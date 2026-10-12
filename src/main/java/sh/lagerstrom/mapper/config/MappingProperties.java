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
package sh.lagerstrom.mapper.config;

import lombok.Data;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Data
@Component
@ConfigurationProperties(prefix = "mapper", ignoreUnknownFields = false)
public class MappingProperties implements InitializingBean {
    private boolean professionalEdition;
    private Rule email;
    private Rule firstName;
    private Rule lastName;
    private Rule tenantName;
    private Rule tenantId;
    private Rule customerName;
    private Rule customerId;
    private Rule parentCustomerName;
    private Rule parentCustomerId;
    private List<Rule> userGroups = new ArrayList<>();

    @Data
    public static class Rule {
        private String attribute;
        private String value;
        private String prefix = "";

        public static Rule attribute(String pointer) {
            Rule rule = new Rule();
            rule.setAttribute(pointer);
            return rule;
        }

        public void validate(String field, boolean id) {
            if ((attribute == null) == (value == null)) {
                throw new IllegalArgumentException(field + " requires exactly one of attribute or value");
            }
            if (attribute != null && !attribute.startsWith("/")) {
                throw new IllegalArgumentException(field + ".attribute must be a JSON Pointer starting with /");
            }
            if (attribute != null) {
                com.fasterxml.jackson.core.JsonPointer.compile(attribute);
            }
            if (prefix == null || (id && !prefix.isEmpty())) {
                throw new IllegalArgumentException(field + " has an invalid prefix");
            }
            if (value != null && value.isBlank()) {
                throw new IllegalArgumentException(field + ".value must not be blank");
            }
            if (id && value != null) {
                parseId(value);
            }
        }
    }

    public static UUID parseId(String value) {
        UUID id = UUID.fromString(value);
        if (!id.toString().equalsIgnoreCase(value)) {
            throw new IllegalArgumentException("Expected a canonical UUID");
        }
        return id;
    }

    @Override
    public void afterPropertiesSet() {
        if (email == null) email = Rule.attribute("/email");
        if (firstName == null) firstName = Rule.attribute("/givenName");
        if (lastName == null) lastName = Rule.attribute("/familyName");
        if (userGroups == null) {
            throw new IllegalArgumentException("user-groups must not be null");
        }
        email.validate("email", false);
        firstName.validate("first-name", false);
        lastName.validate("last-name", false);
        validatePair("tenant", tenantName, tenantId);
        validatePair("customer", customerName, customerId);
        validatePair("parent-customer", parentCustomerName, parentCustomerId);
        if (!professionalEdition && (parentCustomerName != null || parentCustomerId != null || !userGroups.isEmpty())) {
            throw new IllegalArgumentException("Parent customers and user groups require mapper.professional-edition=true");
        }
        for (Rule group : userGroups) {
            if (group == null) {
                throw new IllegalArgumentException("user-groups entries must not be null");
            }
            group.validate("user-groups", false);
        }
    }

    private void validatePair(String field, Rule name, Rule id) {
        if (name != null && id != null) {
            throw new IllegalArgumentException("Configure either " + field + "-name or " + field + "-id, not both");
        }
        if (name != null) {
            name.validate(field + "-name", false);
        }
        if (id != null) {
            id.validate(field + "-id", true);
        }
    }
}

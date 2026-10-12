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
package sh.lagerstrom.mapper;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.BindHandler;
import org.springframework.boot.context.properties.bind.handler.NoUnboundElementsBindHandler;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import sh.lagerstrom.mapper.config.MappingProperties;
import sh.lagerstrom.mapper.controller.CustomOAuth2MapperController;
import org.thingsboard.server.dao.oauth2.OAuth2User;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class MappingTest {
    private static final String USER = """
            {"email":"alice@example.com","givenName":"Alice","familyName":"Smith"}
            """;

    private MappingProperties properties(String yaml) throws Exception {
        var sources = new YamlPropertySourceLoader().load("mapping",
                new ByteArrayResource(yaml.getBytes(StandardCharsets.UTF_8)));
        MappingProperties properties = new Binder(ConfigurationPropertySources.from(sources))
                .bind("mapper", Bindable.of(MappingProperties.class),
                        new NoUnboundElementsBindHandler(BindHandler.DEFAULT)).orElseGet(MappingProperties::new);
        properties.afterPropertiesSet();
        return properties;
    }

    private OAuth2User map(String yaml, String user) throws Exception {
        return new CustomOAuth2MapperController(properties(yaml)).map(user);
    }

    @Test
    void preservesDefaultMapping() throws Exception {
        OAuth2User user = map("server: {port: 10010}", USER);
        assertEquals("alice@example.com", user.getEmail());
        assertEquals("Alice", user.getFirstName());
        assertEquals("Smith", user.getLastName());
        assertEquals("example.com", user.getTenantName());
        assertNull(user.getCustomerId());
        assertNull(user.getCustomerName());
        assertFalse(user instanceof PEOAuth2User);
    }

    @Test
    void bindsConstantsNestedAttributesAndPrefixes() throws Exception {
        OAuth2User user = map("""
                mapper:
                  email:
                    value: service@example.org
                  first-name:
                    attribute: /profile/first
                  tenant-name:
                    attribute: /organization
                    prefix: "Tenant "
                  customer-name:
                    value: Example customer
                """, """
                {"profile":{"first":"Alice"},"familyName":"Smith","organization":"Acme"}
                """);
        assertEquals("service@example.org", user.getEmail());
        assertEquals("Alice", user.getFirstName());
        assertEquals("Tenant Acme", user.getTenantName());
        assertEquals("Example customer", user.getCustomerName());
    }

    @Test
    void mapsIdsAndProfessionalEditionGroups() throws Exception {
        PEOAuth2User user = (PEOAuth2User) map("""
                mapper:
                  professional-edition: true
                  tenant-id:
                    value: bc89e8f0-8ba9-11ea-97a9-0bc52285619a
                  customer-id:
                    attribute: /customerId
                  parent-customer-id:
                    value: e78c0ab0-8ba9-11ea-97a9-0bc52285619a
                  user-groups:
                    - value: Managers
                    - attribute: /groups
                      prefix: "Group "
                    - attribute: /singleGroup
                """, """
                {"email":"alice@example.com","givenName":"Alice","familyName":"Smith",
                 "customerId":"e78c0ab0-8ba9-11ea-97a9-0bc52285619a",
                 "groups":["A","B"],"singleGroup":"C"}
                """);
        assertEquals("bc89e8f0-8ba9-11ea-97a9-0bc52285619a", user.getTenantId().getId().toString());
        assertNull(user.getTenantName());
        assertEquals(user.getCustomerId(), user.getParentCustomerId());
        assertEquals(java.util.List.of("Managers", "Group A", "Group B", "C"), user.getUserGroups());
    }

    @Test
    void mapsParentCustomerName() throws Exception {
        PEOAuth2User user = (PEOAuth2User) map("""
                mapper:
                  professional-edition: true
                  parent-customer-name:
                    value: Parent
                """, USER);
        assertEquals("Parent", user.getParentCustomerName());
    }

    @Test
    void rejectsInvalidConfiguration() {
        for (String yaml : java.util.List.of(
                "mapper: {tenant-name: {value: A}, tenant-id: {value: bc89e8f0-8ba9-11ea-97a9-0bc52285619a}}",
                "mapper: {customer-name: {value: A}, customer-id: {value: bc89e8f0-8ba9-11ea-97a9-0bc52285619a}}",
                "mapper: {email: {attribute: /email, value: fixed}}",
                "mapper: {email: {attribute: email}}",
                "mapper: {tenant-id: {value: invalid}}",
                "mapper: {tenant-id: {value: bc89e8f0-8ba9-11ea-97a9-0bc52285619a, prefix: X}}",
                "mapper: {user-groups: [{value: Managers}]}",
                "mapper: {parent-customer-name: {value: Parent}}",
                "mapper: {tennant-name: {value: Typo}}")) {
            assertThrows(Exception.class, () -> properties(yaml), yaml);
        }
    }

    @Test
    void returnsBadRequestForMalformedOrMissingAttributes() throws Exception {
        var mvc = MockMvcBuilders.standaloneSetup(new CustomOAuth2MapperController(properties("server: {port: 10010}"))).build();
        for (String body : java.util.List.of("{", "[]", "{}", USER.replace("\"Alice\"", "[]"),
                USER.replace("\"Alice\"", "null"), USER.replace("Alice", " "))) {
            mvc.perform(post("/oauth2/mapper").contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isBadRequest());
        }
        mvc.perform(post("/oauth2/mapper").contentType(MediaType.APPLICATION_JSON).content(USER))
                .andExpect(status().isOk());
    }

    @Test
    void rejectsInvalidDynamicIdsAndGroupValues() throws Exception {
        for (String yaml : java.util.List.of(
                "mapper: {customer-id: {attribute: /givenName}}",
                "mapper: {professional-edition: true, user-groups: [{attribute: /missing}]}")) {
            var mvc = MockMvcBuilders.standaloneSetup(new CustomOAuth2MapperController(properties(yaml))).build();
            mvc.perform(post("/oauth2/mapper").contentType(MediaType.APPLICATION_JSON).content(USER))
                    .andExpect(status().isBadRequest());
        }
    }
}

package app.tabikime.kimetabi.support.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@SpringBootTest(
        classes = {
                ApiErrorHandlingTest.TestConfiguration.class,
                GlobalApiExceptionHandler.class,
                TraceIdFilter.class
        }
)
@AutoConfigureMockMvc
class ApiErrorHandlingTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void returnsProblemDetailsAndFieldErrorsForInvalidBody() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/test/validation")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name": ""}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(header().string(TraceIdFilter.RESPONSE_HEADER, matchesPattern("[0-9a-f]{32}")))
                .andExpect(jsonPath("$.type")
                        .value("https://tabikime.app/problems/validation-failed"))
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.message").value("入力内容を確認してください。"))
                .andExpect(jsonPath("$.traceId", matchesPattern("[0-9a-f]{32}")))
                .andExpect(jsonPath("$.fieldErrors", hasSize(1)))
                .andExpect(jsonPath("$.fieldErrors[0].field").value("name"))
                .andReturn();

        assertThat(JsonPath.<String>read(
                result.getResponse().getContentAsString(),
                "$.traceId"
        )).isEqualTo(result.getResponse().getHeader(TraceIdFilter.RESPONSE_HEADER));
    }

    @Test
    void returnsProblemDetailsForInvalidMethodParameter() throws Exception {
        mockMvc.perform(post("/api/test/parameter").queryParam("code", "TOO-LONG"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.fieldErrors", hasSize(1)))
                .andExpect(jsonPath("$.fieldErrors[0].field").value("code"));
    }

    @Test
    void returnsGenericProblemForMalformedJson() throws Exception {
        mockMvc.perform(post("/api/test/validation")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.traceId", matchesPattern("[0-9a-f]{32}")));
    }

    @Test
    void doesNotExposeUnexpectedExceptionDetails() throws Exception {
        mockMvc.perform(post("/api/test/failure"))
                .andExpect(status().isInternalServerError())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
                .andExpect(content().string(not(containsString("database-password"))));
    }

    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration(exclude = DataSourceAutoConfiguration.class)
    static class TestConfiguration {

        @Bean
        TestController testController() {
            return new TestController();
        }
    }

    @RestController
    static class TestController {

        @PostMapping("/api/test/validation")
        void validate(@Valid @RequestBody TestRequest request) {
        }

        @PostMapping("/api/test/failure")
        void fail() {
            throw new IllegalStateException("database-password=secret");
        }

        @PostMapping("/api/test/parameter")
        void validateParameter(@RequestParam @Size(max = 3) String code) {
        }
    }

    record TestRequest(
            @NotBlank
            @Size(max = 10)
            String name
    ) {
    }
}

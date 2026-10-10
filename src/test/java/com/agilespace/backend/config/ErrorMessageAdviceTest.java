package com.agilespace.backend.config;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@DisplayName("ErrorMessageAdvice - mensagem das 4xx chega ao cliente, 5xx não vaza detalhe")
class ErrorMessageAdviceTest {

    @RestController
    static class PlainController {
        @GetMapping("/t/conflict")
        void conflict() {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "A votação não está aberta.");
        }

        @GetMapping("/t/noreason")
        void noReason() {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        }

        @GetMapping("/t/gateway")
        void gateway() {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Jira respondeu 500: java.net.ConnectException host interno 10.0.0.7");
        }

        @GetMapping("/t/boom")
        void boom() {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "NullPointer em SquadService");
        }
    }

    @RestController
    static class OwnHandlerController {
        @GetMapping("/own/conflict")
        void conflict() {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "texto original");
        }

        @ExceptionHandler(ResponseStatusException.class)
        public ResponseEntity<Map<String, String>> local(ResponseStatusException ex) {
            return ResponseEntity.status(ex.getStatusCode()).body(Map.of("message", "tratado no controller"));
        }
    }

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.standaloneSetup(new PlainController(), new OwnHandlerController())
                .setControllerAdvice(new ErrorMessageAdvice())
                .build();
    }

    @Test
    @DisplayName("4xx devolve o status e a mensagem em português")
    void clientErrorKeepsMessage() throws Exception {
        mvc.perform(get("/t/conflict"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("A votação não está aberta."))
                .andExpect(jsonPath("$.error").value("409 CONFLICT"));
    }

    @Test
    @DisplayName("4xx sem reason cai na mensagem padrão")
    void missingReasonUsesDefault() throws Exception {
        mvc.perform(get("/t/noreason"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(ErrorMessageAdvice.DEFAULT_4XX));
    }

    @Test
    @DisplayName("5xx mantém o status mas não vaza o reason")
    void serverErrorDoesNotLeakReason() throws Exception {
        String body = mvc.perform(get("/t/gateway"))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.message").value(ErrorMessageAdvice.GENERIC_5XX))
                .andReturn().getResponse().getContentAsString();
        assertFalse(body.contains("10.0.0.7") || body.contains("ConnectException") || body.contains("Jira respondeu"));

        mvc.perform(get("/t/boom"))
                .andExpect(status().isInternalServerError())
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("NullPointer"))));
    }

    @Test
    @DisplayName("@ExceptionHandler dentro do controller continua tendo prioridade")
    void controllerHandlerWins() throws Exception {
        mvc.perform(get("/own/conflict"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("tratado no controller"));
    }

    @Test
    @DisplayName("Advice roda por último na ordem de precedência")
    void adviceIsLowestPrecedence() {
        org.springframework.core.annotation.Order order =
                ErrorMessageAdvice.class.getAnnotation(org.springframework.core.annotation.Order.class);
        assertEquals(org.springframework.core.Ordered.LOWEST_PRECEDENCE, order.value());
    }
}

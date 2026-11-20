package com.agilespace.backend.service;

import com.agilespace.backend.domain.User;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("CargoNames - nome canônico do cargo (users.job_title)")
class CargoNamesTest {

    @ParameterizedTest
    @ValueSource(strings = {"Developer", "developer", "DEVELOPER", "  Developer  ", "Desenvolvedor", "Desenvolvedora",
            "Desenvolvedor(a)", "desenvolvedor(A)", "Dev", "DEV", "Desenvolvedór", "Dev  "})
    @DisplayName("grafias de desenvolvimento viram Desenvolvedor(a)")
    void desenvolvimento(String raw) {
        assertEquals("Desenvolvedor(a)", CargoNames.canonical(raw));
    }

    @ParameterizedTest
    @ValueSource(strings = {"QA", "qa", " Qa ", "Analista de QA", "analista de qa", "Analista  de   QA", "QA Engineer",
            "Quality Assurance", "Analista de Qualidade", "ANALISTA DE QUALIDADE"})
    @DisplayName("grafias de QA viram Analista de QA")
    void qa(String raw) {
        assertEquals("Analista de QA", CargoNames.canonical(raw));
    }

    @ParameterizedTest
    @ValueSource(strings = {"Agile Master", "Tech Lead", "Product Owner", "People Lead", "Designer", "UX", "UX Designer",
            "SME", "Stakeholder", "Observador", "Desenvolvedor Sênior", "Senior Developer", "Dev Team", "QA Lead",
            "Analista de Qualidade de Software", "Developer Advocate", "Quality"})
    @DisplayName("liderança, design, UX, SME e variações com mais palavras NÃO são mapeados (comparação exata, não 'contém')")
    void naoMapeia(String raw) {
        assertEquals(raw.trim(), CargoNames.canonical(raw));
    }

    @Test
    @DisplayName("outros valores passam como vieram, só aparados; vazio vira null")
    void aparaEVazio() {
        assertEquals("Agile Master", CargoNames.canonical("  Agile Master "));
        assertNull(CargoNames.canonical(null));
        assertNull(CargoNames.canonical(""));
        assertNull(CargoNames.canonical("   "));
    }

    @Test
    @DisplayName("fillIfBlank preenche cargo nulo/em branco com a role canônica e nunca sobrescreve cargo definido")
    void fillIfBlank() {
        User semCargo = User.builder().id("u1").build();
        assertTrue(CargoNames.fillIfBlank(semCargo, "Developer"));
        assertEquals("Desenvolvedor(a)", semCargo.getJobTitle());

        User emBranco = User.builder().id("u2").jobTitle("  ").build();
        assertTrue(CargoNames.fillIfBlank(emBranco, "QA"));
        assertEquals("Analista de QA", emBranco.getJobTitle());

        User comCargo = User.builder().id("u3").jobTitle("Agile Master").build();
        assertFalse(CargoNames.fillIfBlank(comCargo, "Developer"));
        assertEquals("Agile Master", comCargo.getJobTitle());

        User roleVazia = User.builder().id("u4").build();
        assertFalse(CargoNames.fillIfBlank(roleVazia, " "));
        assertNull(roleVazia.getJobTitle());
        assertFalse(CargoNames.fillIfBlank(roleVazia, null));
    }
}

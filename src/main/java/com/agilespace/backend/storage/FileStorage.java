package com.agilespace.backend.storage;

import org.springframework.core.io.Resource;

import java.io.IOException;
import java.io.InputStream;

/**
 * Onde o conteúdo dos arquivos anexados fica guardado. Hoje é disco local (volume do Docker);
 * a interface existe para trocar por Azure Blob Storage na infra da TOTVS sem mexer no serviço.
 */
public interface FileStorage {

    /** Grava o conteúdo sob a chave informada e devolve quantos bytes foram escritos. */
    long store(String key, InputStream content) throws IOException;

    /** Abre o conteúdo; lança {@link IOException} se a chave não existir. */
    Resource load(String key) throws IOException;

    /** Apaga o conteúdo. Chave inexistente não é erro. */
    void delete(String key) throws IOException;
}

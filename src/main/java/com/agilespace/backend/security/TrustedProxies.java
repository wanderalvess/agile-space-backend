package com.agilespace.backend.security;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Descobre o IP real do cliente atrás de um proxy reverso (Caddy). X-Forwarded-For é só um cabeçalho: quem fala
 * direto com o backend escreve o que quiser nele. Por isso ele só vale quando a conexão chega de um proxy que o
 * operador declarou (TRUSTED_PROXIES, lista de IPs/CIDRs); aí o cliente é o último IP da cadeia que não é proxy
 * confiável (o que o nosso proxy acrescentou), nunca o primeiro (que o cliente controla).
 */
final class TrustedProxies {

    private static final Pattern IP_LITERAL = Pattern.compile("^[0-9A-Fa-f:.]+$");

    private record Cidr(byte[] network, int prefixBits) {
        boolean contains(byte[] addr) {
            if (addr.length != network.length) return false;
            int fullBytes = prefixBits / 8;
            for (int i = 0; i < fullBytes; i++) {
                if (addr[i] != network[i]) return false;
            }
            int rest = prefixBits % 8;
            if (rest == 0) return true;
            int mask = (0xFF << (8 - rest)) & 0xFF;
            return (addr[fullBytes] & mask) == (network[fullBytes] & mask);
        }
    }

    private final List<Cidr> trusted;

    TrustedProxies(String config) {
        List<Cidr> list = new ArrayList<>();
        if (config != null) {
            for (String raw : config.split(",")) {
                String entry = raw.trim();
                if (entry.isEmpty()) continue;
                Cidr cidr = parse(entry);
                if (cidr != null) list.add(cidr);
            }
        }
        this.trusted = List.copyOf(list);
    }

    boolean isEmpty() {
        return trusted.isEmpty();
    }

    boolean isTrusted(String ip) {
        byte[] addr = literalBytes(ip);
        if (addr == null) return false;
        for (Cidr c : trusted) {
            if (c.contains(addr)) return true;
        }
        return false;
    }

    /**
     * IP do cliente para o limitador. Sem proxy confiável configurado, ou com a conexão vinda de outro lugar, o
     * cabeçalho é ignorado e vale o endereço da conexão.
     */
    String clientIp(String remoteAddr, String forwardedFor) {
        if (trusted.isEmpty() || !isTrusted(remoteAddr) || forwardedFor == null || forwardedFor.isBlank()) {
            return remoteAddr;
        }
        String[] hops = forwardedFor.split(",");
        for (int i = hops.length - 1; i >= 0; i--) {
            String hop = hops[i].trim();
            if (!IP_LITERAL.matcher(hop).matches() || literalBytes(hop) == null) {
                // Valor que não é IP (ou lixo): não dá pra confiar em nada à esquerda dele.
                return remoteAddr;
            }
            if (!isTrusted(hop)) {
                return hop;
            }
        }
        return remoteAddr;
    }

    private static Cidr parse(String entry) {
        String host = entry;
        Integer prefix = null;
        int slash = entry.indexOf('/');
        if (slash >= 0) {
            host = entry.substring(0, slash).trim();
            try {
                prefix = Integer.parseInt(entry.substring(slash + 1).trim());
            } catch (NumberFormatException e) {
                return null;
            }
        }
        byte[] bytes = literalBytes(host);
        if (bytes == null) return null;
        int max = bytes.length * 8;
        if (prefix == null) prefix = max;
        if (prefix < 0 || prefix > max) return null;
        return new Cidr(bytes, prefix);
    }

    /** Só IP literal; nunca resolve nome (evita consulta de DNS em dado vindo do cliente). */
    private static byte[] literalBytes(String ip) {
        if (ip == null || ip.isBlank() || !IP_LITERAL.matcher(ip.trim()).matches()) return null;
        try {
            return InetAddress.getByName(ip.trim()).getAddress();
        } catch (UnknownHostException e) {
            return null;
        }
    }
}

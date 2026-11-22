package com.familyguard.agent;

/** «Justificar»: validación del mensaje y la foto antes de enviar, y textos del estado de cada solicitud. */
final class UnblockRequestRules {
    static final int MAX_PHOTO = 2 * 1024 * 1024, MIN_MESSAGE = 3, MAX_MESSAGE = 500;
    private UnblockRequestRules() { }

    /** Mensaje limpio, o una excepción con el motivo en palabras para el hijo. */
    static String message(String text) {
        String t = text == null ? "" : text.strip();
        if (t.length() < MIN_MESSAGE) throw new IllegalArgumentException("Escribe por qué necesitas que lo desbloqueen.");
        if (t.length() > MAX_MESSAGE) throw new IllegalArgumentException("El mensaje puede tener como máximo 500 caracteres.");
        return t;
    }

    /** Solo PNG o JPG reales (por su contenido) y hasta 2 MB. */
    static void photo(byte[] bytes) {
        if (bytes == null) return;
        if (bytes.length > MAX_PHOTO) throw new IllegalArgumentException("La foto supera 2 MB. Elige una más pequeña.");
        if (type(bytes) == null) throw new IllegalArgumentException("La foto debe ser una imagen PNG o JPG.");
    }

    static String type(byte[] b) {
        if (b == null) return null;
        if (b.length > 8 && (b[0] & 0xFF) == 0x89 && b[1] == 'P' && b[2] == 'N' && b[3] == 'G') return "image/png";
        if (b.length > 3 && (b[0] & 0xFF) == 0xFF && (b[1] & 0xFF) == 0xD8 && (b[2] & 0xFF) == 0xFF) return "image/jpeg";
        return null;
    }

    /** Estado mostrado junto al elemento bloqueado. */
    static String status(AgentApiClient.UnblockRequest r) {
        if (r == null) return null;
        String reply = r.reply() == null || r.reply().isBlank() ? "" : " · «" + r.reply() + "»";
        return switch (r.state()) {
            case "PENDIENTE" -> "Solicitud enviada · esperando la respuesta de tu padre o madre";
            case "APROBADA" -> "Solicitud aprobada: desbloqueado por " + (r.minutes() == null ? "un tiempo" : r.minutes() + " min") + reply;
            case "RECHAZADA" -> "Solicitud rechazada" + reply;
            default -> null;
        };
    }

    /** Aviso en el área de notificación cuando el padre responde. */
    static String notification(AgentApiClient.UnblockRequest r) {
        return switch (r.state()) {
            case "APROBADA" -> "Tu solicitud fue aprobada: " + r.target() + " queda desbloqueado por "
                    + (r.minutes() == null ? "un tiempo" : r.minutes() + " minutos") + ".";
            case "RECHAZADA" -> "Tu solicitud para " + r.target() + " fue rechazada"
                    + (r.reply() == null || r.reply().isBlank() ? "." : ": «" + r.reply() + "».");
            default -> null;
        };
    }
}

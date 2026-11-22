package com.familyguard.familyguard.service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Cifrado del PIN parental.
 *
 * <ul>
 *   <li>Copia reversible con AES-256-GCM, solo en el servidor, para «Ver PIN» con reautenticación. La clave se
 *   toma de la propiedad o variable {@code FAMILYGUARD_PIN_KEY} (Base64, 32 bytes) o de un archivo de clave que
 *   se crea al primer uso fuera del repositorio. Nunca se escribe en archivos versionados ni en registros.</li>
 *   <li>Verificador PBKDF2-HMAC-SHA256 no reversible, que es lo único que reciben los Agentes.</li>
 * </ul>
 */
public final class PinCifrado {
    public static final int ITERACIONES = 120_000;
    private static final SecureRandom RANDOM = new SecureRandom();
    private final byte[] clave;

    public PinCifrado(byte[] clave) {
        if (clave == null || clave.length != 32) throw new IllegalArgumentException("La clave del PIN debe tener 32 bytes");
        this.clave = clave.clone();
    }

    /** Clave de la configuración o, si no hay, del archivo indicado (se crea con permisos restringidos). */
    public static PinCifrado desde(String claveBase64, Path archivo) {
        if (claveBase64 != null && !claveBase64.isBlank()) return new PinCifrado(Base64.getDecoder().decode(claveBase64.strip()));
        try {
            if (Files.exists(archivo)) return new PinCifrado(Base64.getDecoder().decode(Files.readString(archivo).strip()));
            Files.createDirectories(archivo.toAbsolutePath().getParent());
            byte[] nueva = new byte[32];
            RANDOM.nextBytes(nueva);
            Files.writeString(archivo, Base64.getEncoder().encodeToString(nueva), StandardCharsets.US_ASCII,
                    StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            try { Files.setPosixFilePermissions(archivo, PosixFilePermissions.fromString("rw-------")); }
            catch (UnsupportedOperationException ignored) { /* Windows: la carpeta del perfil ya es privada */ }
            return new PinCifrado(nueva);
        } catch (IOException e) {
            throw new IllegalStateException("No se pudo preparar la clave del PIN parental", e);
        }
    }

    public String cifrar(String pin) {
        try {
            byte[] iv = new byte[12];
            RANDOM.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(clave, "AES"), new GCMParameterSpec(128, iv));
            byte[] cifrado = cipher.doFinal(pin.getBytes(StandardCharsets.UTF_8));
            byte[] todo = Arrays.copyOf(iv, iv.length + cifrado.length);
            System.arraycopy(cifrado, 0, todo, iv.length, cifrado.length);
            return "v1:" + Base64.getEncoder().encodeToString(todo);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("No se pudo cifrar el PIN", e);
        }
    }

    public String descifrar(String valor) {
        try {
            byte[] todo = Base64.getDecoder().decode(valor.substring(3));
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(clave, "AES"), new GCMParameterSpec(128, todo, 0, 12));
            return new String(cipher.doFinal(todo, 12, todo.length - 12), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException | RuntimeException e) {
            throw new IllegalStateException("No se pudo descifrar el PIN (¿cambió la clave del servidor?)", e);
        }
    }

    /** Formato: pbkdf2-sha256$iteraciones$salBase64$hashBase64 (el mismo que comprueba el Agente). */
    public static String verificador(String pin) {
        byte[] sal = new byte[16];
        RANDOM.nextBytes(sal);
        return "pbkdf2-sha256$" + ITERACIONES + "$" + Base64.getEncoder().encodeToString(sal) + "$"
                + Base64.getEncoder().encodeToString(pbkdf2(pin, sal, ITERACIONES));
    }

    public static boolean coincide(String pin, String verificador) {
        String[] partes = verificador == null ? new String[0] : verificador.split("\\$");
        if (partes.length != 4 || !"pbkdf2-sha256".equals(partes[0])) return false;
        byte[] esperado = Base64.getDecoder().decode(partes[3]);
        return MessageDigest.isEqual(esperado, pbkdf2(pin, Base64.getDecoder().decode(partes[2]), Integer.parseInt(partes[1])));
    }

    private static byte[] pbkdf2(String pin, byte[] sal, int iteraciones) {
        try {
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
                    .generateSecret(new PBEKeySpec(pin.toCharArray(), sal, iteraciones, 256)).getEncoded();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    /** 4 a 8 dígitos; no todos iguales ni consecutivos (1234, 9876). */
    public static void validar(String pin) {
        if (pin == null || !pin.matches("\\d{4,8}")) throw new IllegalArgumentException("El PIN debe tener de 4 a 8 dígitos");
        boolean iguales = pin.chars().distinct().count() == 1;
        boolean sube = true, baja = true;
        for (int i = 1; i < pin.length(); i++) {
            int dif = pin.charAt(i) - pin.charAt(i - 1);
            sube &= dif == 1; baja &= dif == -1;
        }
        if (iguales || sube || baja) throw new IllegalArgumentException("Elige un PIN menos fácil de adivinar");
    }
}

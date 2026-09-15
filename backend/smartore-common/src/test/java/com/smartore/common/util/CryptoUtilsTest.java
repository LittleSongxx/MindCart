package com.smartore.common.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class CryptoUtilsTest {

    @Test
    void encryptDecryptRoundTrip() {
        String key = CryptoUtils.generateKey();
        String plain = "sk-test-1234567890";
        String cipher = CryptoUtils.encrypt(plain, key);
        assertNotEquals(plain, cipher);
        assertEquals(plain, CryptoUtils.decrypt(cipher, key));
    }

    @Test
    void samePlaintextDifferentCipherPerCall() {
        String key = CryptoUtils.generateKey();
        // 随机 IV：同明文两次密文不同
        assertNotEquals(CryptoUtils.encrypt("same", key), CryptoUtils.encrypt("same", key));
    }

    @Test
    void wrongKeyFails() {
        String cipher = CryptoUtils.encrypt("secret", CryptoUtils.generateKey());
        assertThrows(IllegalStateException.class,
                () -> CryptoUtils.decrypt(cipher, CryptoUtils.generateKey()));
    }

    @Test
    void blankSafe() {
        assertNull(CryptoUtils.encrypt(null, "k"));
        assertEquals("", CryptoUtils.encrypt("", "k"));
    }}

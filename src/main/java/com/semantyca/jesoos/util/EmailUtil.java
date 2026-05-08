package com.semantyca.jesoos.util;

import java.util.Locale;

public final class EmailUtil {

    private EmailUtil() {}

    public static String normalize(String email) {
        return email == null ? null : email.trim().toLowerCase(Locale.ROOT);
    }
}

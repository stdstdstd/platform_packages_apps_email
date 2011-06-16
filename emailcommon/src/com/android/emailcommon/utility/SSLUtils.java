/*
 * Copyright (C) 2010 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.emailcommon.utility;

import android.content.Context;
import android.net.SSLCertificateSocketFactory;
import android.security.KeyChain;
import android.security.KeyChainException;
import android.util.Log;

import java.net.Socket;
import java.security.Principal;
import java.security.PrivateKey;
import java.security.cert.X509Certificate;
import java.util.Arrays;

import javax.net.ssl.KeyManager;
import javax.net.ssl.X509ExtendedKeyManager;

public class SSLUtils {
    private static SSLCertificateSocketFactory sInsecureFactory;
    private static SSLCertificateSocketFactory sSecureFactory;

    private static final boolean LOG_ENABLED = false;
    private static final String TAG = "Email.Ssl";

    /**
     * Returns a {@link javax.net.ssl.SSLSocketFactory}.
     * Optionally bypass all SSL certificate checks.
     *
     * @param insecure if true, bypass all SSL certificate checks
     */
    public synchronized static final SSLCertificateSocketFactory getSSLSocketFactory(
            boolean insecure) {
        if (insecure) {
            if (sInsecureFactory == null) {
                sInsecureFactory = (SSLCertificateSocketFactory)
                        SSLCertificateSocketFactory.getInsecure(0, null);
            }
            return sInsecureFactory;
        } else {
            if (sSecureFactory == null) {
                sSecureFactory = (SSLCertificateSocketFactory)
                        SSLCertificateSocketFactory.getDefault(0, null);
            }
            return sSecureFactory;
        }
    }

    /**
     * Returns a {@link org.apache.http.conn.ssl.SSLSocketFactory SSLSocketFactory} for use with the
     * Apache HTTP stack.
     */
    public static SSLSocketFactory getHttpSocketFactory(boolean insecure, KeyManager keyManager) {
        SSLCertificateSocketFactory underlying = getSSLSocketFactory(insecure);
        if (keyManager != null) {
            underlying.setKeyManagers(new KeyManager[] { keyManager });
        }
        return new SSLSocketFactory(underlying);
    }

    /**
     * Escapes the contents a string to be used as a safe scheme name in the URI according to
     * http://tools.ietf.org/html/rfc3986#section-3.1
     *
     * This does not ensure that the first character is a letter (which is required by the RFC).
     */
    public static String escapeForSchemeName(String s) {
        // According to the RFC, scheme names are case-insensitive.
        s = s.toLowerCase();

        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (Character.isLetter(c) || Character.isDigit(c)
                    || ('-' == c) || ('.' == c)) {
                // Safe - use as is.
                sb.append(c);
            } else if ('+' == c) {
                // + is used as our escape character, so double it up.
                sb.append("++");
            } else {
                // Unsafe - escape.
                sb.append('+').append((int) c);
            }
        }
        return sb.toString();
    }

    @SuppressWarnings("unused")
    private static abstract class StubKeyManager extends X509ExtendedKeyManager {
        @Override public abstract String chooseClientAlias(
                String[] keyTypes, Principal[] issuers, Socket socket);

        @Override public abstract X509Certificate[] getCertificateChain(String alias);

        @Override public abstract PrivateKey getPrivateKey(String alias);


        // The following methods are unused.

        @Override
        public final String chooseServerAlias(
                String keyType, Principal[] issuers, Socket socket) {
            // not a client SSLSocket callback
            throw new UnsupportedOperationException();
        }

        @Override
        public final String[] getClientAliases(String keyType, Principal[] issuers) {
            // not a client SSLSocket callback
            throw new UnsupportedOperationException();
        }

        @Override
        public final String[] getServerAliases(String keyType, Principal[] issuers) {
            // not a client SSLSocket callback
            throw new UnsupportedOperationException();
        }
    }

    /**
     * A dummy {@link KeyManager} which throws a {@link CertificateRequestedException} if the
     * server requests a certificate.
     */
    public static class TrackingKeyManager extends StubKeyManager {
        @Override
        public String chooseClientAlias(String[] keyTypes, Principal[] issuers, Socket socket) {
            if (LOG_ENABLED) {
                Log.i(TAG, "TrackingKeyManager: requesting a client cert alias for "
                        + Arrays.toString(keyTypes));
            }
            throw new CertificateRequestedException();
        }

        @Override
        public X509Certificate[] getCertificateChain(String alias) {
            return null;
        }

        @Override
        public PrivateKey getPrivateKey(String alias) {
            return null;
        }
    }

    /**
     * An exception indicating that a server requested a client certificate but none was
     * available to be presented.
     */
    public static class CertificateRequestedException extends RuntimeException {
    }

    /**
     * A {@link KeyManager} that reads uses credentials stored in the system {@link KeyChain}.
     */
    public static class KeyChainKeyManager extends StubKeyManager {
        private final String mClientAlias;
        private final X509Certificate[] mCertificateChain;
        private final PrivateKey mPrivateKey;

        /**
         * Builds an instance of a KeyChainKeyManager using the given certificate alias.
         * If for any reason retrieval of the credentials from the system {@link KeyChain} fails,
         * a {@code null} value will be returned.
         */
        public static KeyChainKeyManager fromAlias(Context context, String alias) {
            X509Certificate[] certificateChain;
            try {
                certificateChain = KeyChain.getCertificateChain(context, alias);
            } catch (KeyChainException e) {
                logError(alias, "certificate chain", e);
                return null;
            } catch (InterruptedException e) {
                logError(alias, "certificate chain", e);
                return null;
            }

            PrivateKey privateKey;
            try {
                privateKey = KeyChain.getPrivateKey(context, alias);
            } catch (KeyChainException e) {
                logError(alias, "private key", e);
                return null;
            } catch (InterruptedException e) {
                logError(alias, "private key", e);
                return null;
            }

            return new KeyChainKeyManager(alias, certificateChain, privateKey);
        }

        private static void logError(String alias, String type, Exception ex) {
            Log.e(TAG, "Unable to retrieve " + type + " for [" + alias + "] due to " + ex);
        }

        private KeyChainKeyManager(
                String clientAlias, X509Certificate[] certificateChain, PrivateKey privateKey) {
            mClientAlias = clientAlias;
            mCertificateChain = certificateChain;
            mPrivateKey = privateKey;
        }


        @Override
        public String chooseClientAlias(String[] keyTypes, Principal[] issuers, Socket socket) {
            if (LOG_ENABLED) {
                Log.i(TAG, "Requesting a client cert alias for " + Arrays.toString(keyTypes));
            }
            return mClientAlias;
        }

        @Override
        public X509Certificate[] getCertificateChain(String alias) {
            if (LOG_ENABLED) {
                Log.i(TAG, "Requesting a client certificate chain for alias [" + alias + "]");
            }
            return mCertificateChain;
        }

        @Override
        public PrivateKey getPrivateKey(String alias) {
            if (LOG_ENABLED) {
                Log.i(TAG, "Requesting a client private key for alias [" + alias + "]");
            }
            return mPrivateKey;
        }
    }
}

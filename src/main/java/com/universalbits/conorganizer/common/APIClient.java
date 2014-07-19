package com.universalbits.conorganizer.common;

import javax.net.ssl.*;
import java.io.*;
import java.net.*;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

public class APIClient {
    private static final Logger LOGGER = Logger.getLogger(APIClient.class.getName());

    private final String name;
    private Properties prop;
    private File propFile;
    private MessageDigest digest;

    public static final String PROPERTY_NAME = "name";
    public static final String PROPERTY_UUID = "uuid";
    public static final String PROPERTY_TOKEN = "token";
    public static final String PROPERTY_COUNTER = "counter";
    public static final String PROPERTY_KEY = "key";
    public static final String PROPERTY_URL_PREFIX = "urlPrefix";

    public APIClient(String name) {
        this(name, null);
    }

    public APIClient(String name, Properties prop) {
        APIClient.setupHTTPS();
        this.name = name;
        if (prop != null) {
            this.prop = prop;
        } else {
            this.prop = prop = new Properties();
            final File homeDir = new File(System.getProperty("user.home"));
            propFile = new File(homeDir, name + ".properties");
            try {
                if (propFile.exists()) {
                    prop.load(new FileInputStream(propFile));
                }
            } catch (IOException ioe) {
                ioe.printStackTrace();
            }
        }
        String uuid = prop.getProperty(PROPERTY_UUID, "");
        if (uuid.length() != 32) {
            uuid = UUID.randomUUID().toString().replaceAll("-", "");
            prop.setProperty(PROPERTY_UUID, uuid);
        }
        final String clientName = prop.getProperty(PROPERTY_NAME, getHostname());
        prop.setProperty(PROPERTY_NAME, clientName);
        final String token = prop.getProperty(PROPERTY_TOKEN, "");
        prop.setProperty(PROPERTY_TOKEN, token);
        save();
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            e.printStackTrace();
        }
    }

    private void save() {
        if (propFile != null) {
            try {
                prop.store(new FileOutputStream(propFile), "settings for " + name);
            } catch (IOException ioe) {
                ioe.printStackTrace();
            }
        }
    }

    public void setToken(String token) {
        prop.put(PROPERTY_TOKEN, token);
        save();
    }

    public boolean hasToken() {
        String token = getProperty(PROPERTY_TOKEN);
        return token != null && !token.isEmpty();
    }

    public String getClientName() {
        return prop.getProperty(PROPERTY_NAME);
    }

    private static String getHostname() {
        String hostname = "localhost";
        try {
            InetAddress address = InetAddress.getLocalHost();
            hostname = address.getHostName();
        } catch (UnknownHostException e) {
            e.printStackTrace();
        }
        return hostname;
    }

    public String getProperty(String name) {
        return prop.getProperty(name);
    }

    public Map<String, String> getRequestParams() {
        final Map<String, String> params = new HashMap<>();
        final String uuid = prop.getProperty(PROPERTY_UUID);
        final String token = prop.getProperty(PROPERTY_TOKEN);
        String counter = prop.getProperty(PROPERTY_COUNTER, "0");
        // System.out.println("counter=" + counter);
        counter = (Integer.parseInt(counter) + 1) + "";
        prop.setProperty(PROPERTY_COUNTER, counter);
        save();
        String key = "";
        if (token != null && token.length() == 8) {
            try {
                final String keyContent = uuid + "-" + token + "-" + counter;
                // System.out.println("keyContent=" + keyContent);
                byte[] hash = digest.digest((keyContent).getBytes("UTF-8"));
                final StringBuilder hexString = new StringBuilder();
                for (byte b : hash) {
                    String hex = Integer.toHexString(0xff & b);
                    if (hex.length() == 1) {
                        hexString.append('0');
                    }
                    hexString.append(hex);
                }
                key = hexString.toString();
            } catch (UnsupportedEncodingException e) {
                e.printStackTrace();
            }
        }
        params.put(PROPERTY_UUID, uuid);
        params.put(PROPERTY_COUNTER, counter);
        params.put(PROPERTY_KEY, key);
        return params;
    }

    public URL getRequestUrl(String service, Map<String, String> params) {
        final HashMap<String, String> reqParams = new HashMap<>();
        reqParams.putAll(params);
        reqParams.putAll(getRequestParams());
        String urlPrefix = prop.getProperty(PROPERTY_URL_PREFIX);
        StringBuilder builder = new StringBuilder(512);
        builder.append(urlPrefix);
        builder.append(service);
        builder.append("?");
        boolean first = true;
        try {
            for (String key : reqParams.keySet()) {
                if (!first) {
                    builder.append("&");
                }
                first = false;
                final String value = reqParams.get(key);
                builder.append(URLEncoder.encode(key, "ISO-8859-1"));
                builder.append("=");
                builder.append(URLEncoder.encode(value, "ISO-8859-1"));
            }
        } catch (UnsupportedEncodingException e) {
            LOGGER.log(Level.SEVERE, "error building url", e);
        }
        URL url = null;
        try {
            url = new URL(builder.toString());
        } catch (MalformedURLException e) {
            e.printStackTrace();
        }
        return url;
    }

    public static String getUrlAsString(final URL url) throws IOException {
        final URLConnection connection = url.openConnection();
        connection.setConnectTimeout(45000);
        connection.setReadTimeout(45000);
        final InputStream inputStream = connection.getInputStream();
        final InputStreamReader inputStreamReader = new InputStreamReader(inputStream);
        final BufferedReader in = new BufferedReader(inputStreamReader);
        final StringBuilder response = new StringBuilder();
        String inputLine;
        while ((inputLine = in.readLine()) != null) {
            response.append(inputLine);
        }
        in.close();
        return response.toString();
    }

    private static void setupHTTPS() {
        // Create a trust manager that does not validate certificate chains
        TrustManager[] trustAllCerts = new TrustManager[]{new X509TrustManager() {
            public java.security.cert.X509Certificate[] getAcceptedIssuers() {
                return null;
            }

            public void checkClientTrusted(java.security.cert.X509Certificate[] certs, String authType) {
            }

            public void checkServerTrusted(java.security.cert.X509Certificate[] certs, String authType) {
            }
        }};

        // Install the all-trusting trust manager
        try {
            SSLContext sc = SSLContext.getInstance("SSL");
            sc.init(null, trustAllCerts, new java.security.SecureRandom());
            HttpsURLConnection.setDefaultSSLSocketFactory(sc.getSocketFactory());
        } catch (Exception e) {
            LOGGER.log(Level.INFO, "error installing trust manager", e);
        }

        HostnameVerifier hv = new HostnameVerifier() {
            @Override
            public boolean verify(String urlHostName, SSLSession session) {
                if (!session.getPeerHost().equals(urlHostName)) {
                    LOGGER.warning("Warning: URL Host: " + urlHostName + " vs. " + session.getPeerHost());
                }
                return true;
            }
        };
        HttpsURLConnection.setDefaultHostnameVerifier(hv);
    }

}

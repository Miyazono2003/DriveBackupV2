package ratismal.drivebackup.uploaders;

import org.bukkit.command.CommandSender;
import org.json.JSONObject;

import okhttp3.FormBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import ratismal.drivebackup.UploadThread.UploadLogger;
import ratismal.drivebackup.handler.commandHandler.BasicCommands;
import ratismal.drivebackup.plugin.DriveBackup;
import ratismal.drivebackup.uploaders.googledrive.GoogleDriveUploader;
import ratismal.drivebackup.util.Logger;
import ratismal.drivebackup.util.MessageUtil;
import ratismal.drivebackup.util.NetUtil;
import ratismal.drivebackup.util.SchedulerUtil;

import org.bukkit.Bukkit;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;

import main.java.credentials.AuthenticatorCredentials;
import main.java.credentials.OneDriveCredentials;

import static ratismal.drivebackup.config.Localization.intl;

public class Authenticator {
    /**
     * Endpoints
     */
    private static String REQUEST_CODE_ENDPOINT = "https://drivebackup.web.app/pin";
    private static String POLL_VERIFICATION_ENDPOINT = "https://drivebackup.web.app/token";
    private static String ONEDRIVE_REQUEST_CODE_ENDPOINT = "https://login.microsoftonline.com/common/oauth2/v2.0/devicecode";
    private static String ONEDRIVE_POLL_VERIFICATION_ENDPOINT = "https://login.microsoftonline.com/common/oauth2/v2.0/token";

    /**
     * Global instance of the HTTP client
     */
    private static final OkHttpClient httpClient = new OkHttpClient();

    private static int taskId = -1;

    public enum AuthenticationProvider {
        GOOGLE_DRIVE("Google Drive", "googledrive", "/GoogleDriveCredential.json"),
        ONEDRIVE("OneDrive", "onedrive", "/OneDriveCredential.json"),
        DROPBOX("Dropbox", "dropbox", "/DropboxCredential.json");

        private final String name;
        private final String id;
        private final String credStoreLocation;

        AuthenticationProvider(final String name, final String id, final String credStoreLocation) {
            this.name = name;
            this.id = id;
            this.credStoreLocation = credStoreLocation;
        }

        public String getName() {
            return name;
        }

        public String getId() {
            return id;
        }

        public String getCredStoreLocation() {
            return DriveBackup.getInstance().getDataFolder().getAbsolutePath() + credStoreLocation;
        }
    }

    /**
     * Attempt to authenticate a user with the specified authentication provider 
     * using the OAuth 2.0 device authorization grant flow
     * 
     * @param provider an {@code AuthenticationProvider}
     * @param initiator user who initiated the authentication
     */
    public static void authenticateUser(final AuthenticationProvider provider, final CommandSender initiator) {
        DriveBackup plugin = DriveBackup.getInstance();

        Logger logger = (input, placeholders) -> {
            MessageUtil.Builder().mmText(input, placeholders).to(initiator).toConsole(false).send();
        };

        cancelPollTask();

        try {
            FormBody.Builder requestBody = new FormBody.Builder()
                .add("type", provider.getId());

            String requestEndpoint;
            if (provider == AuthenticationProvider.ONEDRIVE) {
                requestBody.add("client_id", OneDriveCredentials.CLIENT_ID);
                requestBody.add("scope", "offline_access Files.ReadWrite");

                requestEndpoint = ONEDRIVE_REQUEST_CODE_ENDPOINT;
            } else {
                requestBody.add("client_secret", AuthenticatorCredentials.CLIENT_SECRET);
                
                requestEndpoint = REQUEST_CODE_ENDPOINT;
            }

            Request request = new Request.Builder()
                .url(requestEndpoint)
                .post(requestBody.build())
                .build();

            Response response = httpClient.newCall(request).execute();
            JSONObject parsedResponse = new JSONObject(response.body().string());
            response.close();

            String userCode = parsedResponse.getString("user_code");
            final String deviceCode = parsedResponse.getString("device_code");
            String verificationUri = parsedResponse.getString("verification_uri");
            long responseCheckDelay = SchedulerUtil.sToTicks(parsedResponse.getLong("interval"));

            logger.log(
                intl("link-account-code"),
                "link-url", verificationUri,
                "link-code", userCode,
                "provider", provider.getName());

            taskId = plugin.getServer().getScheduler().scheduleSyncRepeatingTask(plugin, new Runnable() {
                @Override
                public void run() {
                    try {
                        FormBody.Builder requestBody = new FormBody.Builder()
                            .add("device_code", deviceCode)
                            .add("user_code", userCode);

                        String requestEndpoint;
                        if (provider == AuthenticationProvider.ONEDRIVE) {
                            requestBody.add("client_id", OneDriveCredentials.CLIENT_ID);
                            requestBody.add("grant_type", "urn:ietf:params:oauth:grant-type:device_code");

                            requestEndpoint = ONEDRIVE_POLL_VERIFICATION_ENDPOINT;
                        } else {
                            requestBody.add("client_secret", AuthenticatorCredentials.CLIENT_SECRET);

                            requestEndpoint = POLL_VERIFICATION_ENDPOINT;
                        }
                
                        Request request = new Request.Builder()
                            .url(requestEndpoint)
                            .post(requestBody.build())
                            .build();
                        
                        Response response = httpClient.newCall(request).execute();
                        JSONObject parsedResponse = new JSONObject(response.body().string());
                        response.close();

                        if (parsedResponse.has("refresh_token")) {
                            saveRefreshToken(provider, (String) parsedResponse.get("refresh_token"));

                            if (provider.getId() == "googledrive") {
                                UploadLogger uploadLogger = new UploadLogger() {
                                    @Override
                                    public void log(String input, String... placeholders) {
                                        MessageUtil.Builder()
                                            .mmText(input, placeholders)
                                            .to(initiator)
                                            .send();
                                    }
                                };

                                new GoogleDriveUploader(uploadLogger).setupSharedDrives(initiator);
                            } else {
                                Authenticator.linkSuccess(initiator, provider, logger);
                            }

                            cancelPollTask();
                        } else if (
                            (provider == AuthenticationProvider.ONEDRIVE && !parsedResponse.getString("error").equals("authorization_pending")) ||
                            (provider != AuthenticationProvider.ONEDRIVE && !parsedResponse.get("msg").equals("code_not_authenticated"))
                            ) {

                            MessageUtil.Builder().text(parsedResponse.toString()).send();
                            throw new UploadException();
                        }

                    } catch (Exception exception) {
                        NetUtil.catchException(exception, "drivebackup.web.app", logger);

                        Authenticator.linkFail(provider, logger);
                        MessageUtil.sendConsoleException(exception);

                        cancelPollTask();
                    }
                }
            }, responseCheckDelay, responseCheckDelay);
        } catch (Exception exception) {
            NetUtil.catchException(exception, "drivebackup.web.app", logger);

            Authenticator.linkFail(provider, logger);
            MessageUtil.sendConsoleException(exception);
        }
    }

    private static void cancelPollTask() {
        if (taskId != -1) {
            Bukkit.getScheduler().cancelTask(taskId);
            taskId = -1;
        }
    }

    public static void linkSuccess(CommandSender initiator, AuthenticationProvider provider, Logger logger) {
        logger.log(intl("link-provider-complete"), "provider", provider.getName());

        enableBackupMethod(provider, logger);

        DriveBackup.reloadLocalConfig();

        BasicCommands.sendBriefBackupList(initiator);
    }

    public static void linkFail(AuthenticationProvider provider, Logger logger) {
        logger.log(intl("link-provider-failed"), "provider", provider.getName());
    }

    private static void saveRefreshToken(AuthenticationProvider provider, String token) throws Exception {
        JSONObject jsonObject = new JSONObject();
        jsonObject.put("refresh_token", token);

        FileWriter file = new FileWriter(provider.getCredStoreLocation());
        file.write(jsonObject.toString());
        file.close();
    }

    private static void enableBackupMethod(AuthenticationProvider provider, Logger logger) {
        DriveBackup plugin = DriveBackup.getInstance();

        if (!plugin.getConfig().getBoolean(provider.getId() + ".enabled")) {
            logger.log("Automatically enabled " + provider.getName() + " backups");
            plugin.getConfig().set(provider.getId() + ".enabled", true);
            plugin.saveConfig();
        }
    }

    public static String getRefreshToken(AuthenticationProvider provider) {
        try {
            String clientJSON = processCredentialJsonFile(provider);
            JSONObject clientJsonObject = new JSONObject(clientJSON);
    
            String readRefreshToken = (String) clientJsonObject.get("refresh_token");
    
            if (readRefreshToken == null || readRefreshToken.isEmpty()) {
                throw new Exception();
            }

            return readRefreshToken;
        } catch (Exception e) {
            return "";
        }
    }

    public static boolean hasRefreshToken(AuthenticationProvider provider) {
        // what am i doing with my life
        return !getRefreshToken(provider).isEmpty();
    }

    /*public static String getAccessToken(AuthenticationProvider provider) {

    }*/

    private static String processCredentialJsonFile(AuthenticationProvider provider) throws IOException {
        BufferedReader br = new BufferedReader(new FileReader(provider.getCredStoreLocation()));
        StringBuilder sb = new StringBuilder();
        String line = br.readLine();

        while (line != null) {
            sb.append(line);
            line = br.readLine();
        }
        
        String result = sb.toString();
        br.close(); 

        return result;
    }
}

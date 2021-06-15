/*
 * Copyright (C) 2020 Finn Herzfeld
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package io.finn.signald;

import io.finn.signald.clientprotocol.v1.JsonAddress;
import io.finn.signald.clientprotocol.v1.JsonGroupV2Info;
import io.finn.signald.db.*;
import io.finn.signald.exceptions.InvalidAddressException;
import io.finn.signald.exceptions.InvalidRecipientException;
import io.finn.signald.exceptions.NoSuchAccountException;
import io.finn.signald.exceptions.UnknownGroupException;
import io.finn.signald.jobs.*;
import io.finn.signald.storage.*;
import io.finn.signald.util.*;
import okhttp3.Interceptor;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.asamk.signal.GroupNotFoundException;
import org.asamk.signal.NotAGroupMemberException;
import org.asamk.signal.TrustLevel;
import org.signal.libsignal.metadata.*;
import org.signal.libsignal.metadata.certificate.CertificateValidator;
import org.signal.zkgroup.InvalidInputException;
import org.signal.zkgroup.VerificationFailedException;
import org.signal.zkgroup.profiles.ProfileKey;
import org.whispersystems.libsignal.*;
import org.whispersystems.libsignal.UntrustedIdentityException;
import org.whispersystems.libsignal.ecc.Curve;
import org.whispersystems.libsignal.ecc.ECKeyPair;
import org.whispersystems.libsignal.ecc.ECPublicKey;
import org.whispersystems.libsignal.fingerprint.Fingerprint;
import org.whispersystems.libsignal.fingerprint.FingerprintParsingException;
import org.whispersystems.libsignal.fingerprint.FingerprintVersionMismatchException;
import org.whispersystems.libsignal.state.PreKeyRecord;
import org.whispersystems.libsignal.state.SignedPreKeyRecord;
import org.whispersystems.libsignal.util.Medium;
import org.whispersystems.libsignal.util.guava.Optional;
import org.whispersystems.signalservice.api.SignalServiceAccountManager;
import org.whispersystems.signalservice.api.SignalServiceMessagePipe;
import org.whispersystems.signalservice.api.SignalServiceMessageReceiver;
import org.whispersystems.signalservice.api.SignalServiceMessageSender;
import org.whispersystems.signalservice.api.account.AccountAttributes;
import org.whispersystems.signalservice.api.crypto.*;
import org.whispersystems.signalservice.api.groupsv2.ClientZkOperations;
import org.whispersystems.signalservice.api.messages.*;
import org.whispersystems.signalservice.api.messages.multidevice.*;
import org.whispersystems.signalservice.api.profiles.ProfileAndCredential;
import org.whispersystems.signalservice.api.profiles.SignalServiceProfile;
import org.whispersystems.signalservice.api.push.ContactTokenDetails;
import org.whispersystems.signalservice.api.push.SignalServiceAddress;
import org.whispersystems.signalservice.api.push.TrustStore;
import org.whispersystems.signalservice.api.push.exceptions.MissingConfigurationException;
import org.whispersystems.signalservice.api.util.DeviceNameUtil;
import org.whispersystems.signalservice.api.util.StreamDetails;
import org.whispersystems.signalservice.api.util.UptimeSleepTimer;
import org.whispersystems.signalservice.api.util.UuidUtil;
import org.whispersystems.signalservice.internal.configuration.*;
import org.whispersystems.signalservice.internal.push.SignalServiceProtos;
import org.whispersystems.signalservice.internal.push.UnsupportedDataMessageException;
import org.whispersystems.signalservice.internal.push.VerifyAccountResponse;
import org.whispersystems.signalservice.internal.util.concurrent.ListenableFuture;
import org.whispersystems.util.Base64;

import java.io.*;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.sql.SQLException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;
import org.whispersystems.signalservice.api.account.AccountAttributes.Capabilities;

import static java.nio.file.attribute.PosixFilePermission.*;
import static org.whispersystems.signalservice.internal.util.Util.isEmpty;

public class Manager {
  private final Logger logger;
  private final static TrustStore TRUST_STORE = new WhisperTrustStore();
  public final static SignalServiceConfiguration serviceConfiguration = Manager.generateSignalServiceConfiguration();
  private final static String USER_AGENT = BuildConfig.USER_AGENT;
  private static final Capabilities SERVICE_CAPABILITIES = new Capabilities(false, true, false, true, true);
  private final static int ACCOUNT_REFRESH_VERSION = 2;

  public final static int PREKEY_MINIMUM_COUNT = 20;
  private final static int PREKEY_BATCH_SIZE = 100;
  private final static int MAX_ATTACHMENT_SIZE = 150 * 1024 * 1024;
  public final static long AVATAR_DOWNLOAD_FAILSAFE_MAX_SIZE = 10 * 1024 * 1024;

  private static final ConcurrentHashMap<String, Manager> managers = new ConcurrentHashMap<>();
  private static final ConcurrentHashMap<String, Manager> pendingManagers = new ConcurrentHashMap<>();

  private static String dataPath;
  private static String attachmentsPath;
  private static String avatarsPath;

  private AccountData accountData;

  private GroupsV2Manager groupsV2Manager;
  private SignalServiceMessagePipe messagePipe = null;
  private final SignalServiceMessagePipe unidentifiedMessagePipe = null;

  private final UptimeSleepTimer sleepTimer = new UptimeSleepTimer();

  public static SignalServiceConfiguration generateSignalServiceConfiguration() {
    final Interceptor userAgentInterceptor = chain -> chain.proceed(chain.request().newBuilder().header("User-Agent", USER_AGENT).build());

    Map<Integer, SignalCdnUrl[]> signalCdnUrlMap = new HashMap<>();
    signalCdnUrlMap.put(0, new SignalCdnUrl[] {new SignalCdnUrl(BuildConfig.SIGNAL_CDN_URL, TRUST_STORE)});
    // unclear why there is no CDN 1
    signalCdnUrlMap.put(2, new SignalCdnUrl[] {new SignalCdnUrl(BuildConfig.SIGNAL_CDN2_URL, TRUST_STORE)});

    try {
      return new SignalServiceConfiguration(new SignalServiceUrl[] {new SignalServiceUrl(BuildConfig.SIGNAL_URL, TRUST_STORE)}, signalCdnUrlMap,
                                            new SignalContactDiscoveryUrl[] {new SignalContactDiscoveryUrl(BuildConfig.SIGNAL_CONTACT_DISCOVERY_URL, TRUST_STORE)},
                                            new SignalKeyBackupServiceUrl[] {new SignalKeyBackupServiceUrl(BuildConfig.SIGNAL_KEY_BACKUP_URL, TRUST_STORE)},
                                            new SignalStorageUrl[] {new SignalStorageUrl(BuildConfig.SIGNAL_STORAGE_URL, TRUST_STORE)},
                                            Collections.singletonList(userAgentInterceptor), Optional.absent(), Optional.absent(),
                                            Base64.decode(BuildConfig.SIGNAL_ZK_GROUP_SERVER_PUBLIC_PARAMS_HEX));
    } catch (IOException e) {
      LogManager.getLogger("manager").catching(e);
      throw new AssertionError(e);
    }
  }

  public static Manager get(UUID uuid) throws SQLException, NoSuchAccountException, IOException {
    Logger logger = LogManager.getLogger("manager");
    if (managers.containsKey(uuid.toString())) {
      return managers.get(uuid.toString());
    }
    AccountData accountData = AccountData.load(AccountsTable.getFile(uuid));
    Manager m = new Manager(accountData);
    managers.put(uuid.toString(), m);

    if (accountData.address.uuid == null && m.getAccountManager().getOwnUuid() != null) {
      accountData.setUUID(m.getAccountManager().getOwnUuid());
      accountData.save();
    }
    m.groupsV2Manager = new GroupsV2Manager(m.getAccountManager().getGroupsV2Api(), accountData.groupsV2, accountData.profileCredentialStore, accountData.getUUID());
    RefreshPreKeysJob.runIfNeeded(m.getUUID());
    m.refreshAccountIfNeeded();
    try {
      m.getRecipientProfileKeyCredential(m.getOwnAddress());
    } catch (InterruptedException | ExecutionException | TimeoutException ignored) {
    }

    logger.info("created a manager for " + accountData.address.toRedactedString());
    return m;
  }

  public static Manager get(String e164) throws IOException, NoSuchAccountException, SQLException {
    AddressResolver resolver = new AddressUtil();
    SignalServiceAddress address = resolver.resolve(e164);
    if (!address.getUuid().isPresent()) {
      throw new NoSuchAccountException(e164);
    }
    return Manager.get(address.getUuid().get());
  }

  public static Manager getPending(String e164) {
    Logger logger = LogManager.getLogger("new-account-manager");
    if (pendingManagers.containsKey(e164)) {
      return pendingManagers.get(e164);
    }
    Manager m = new Manager(e164);
    pendingManagers.put(e164, m);
    m.accountData.setPending();
    logger.info("Created a manager for " + Util.redact(e164));
    return m;
  }

  public static List<Manager> getAll() {
    Logger logger = LogManager.getLogger("manager");
    // We have to create a manager for each account that we're listing, which is all of them :/
    List<Manager> allManagers = new LinkedList<>();
    File[] allAccounts = new File(dataPath).listFiles();
    if (allAccounts == null) {
      return allManagers;
    }
    for (File account : allAccounts) {
      if (!account.isDirectory()) {
        try {
          allManagers.add(Manager.get(account.getName()));
        } catch (IOException | NoSuchAccountException | SQLException e) {
          logger.warn("Failed to load account from " + account.getAbsolutePath() + ": " + e.getMessage());
          e.printStackTrace();
        }
      }
    }
    return allManagers;
  }

  // creates a Manager for an account that has not completed registration
  Manager(String e164) {
    logger = LogManager.getLogger("manager-" + Util.redact(e164));
    logger.info("Creating new manager for " + Util.redact(e164));
    try {
      accountData = AccountData.load(new File(Manager.getFileName(e164)));
    } catch (IOException e) {
      accountData = new AccountData(e164);
    }
  }

  Manager(AccountData a) {
    logger = LogManager.getLogger("manager-" + Util.redact(a.username));
    accountData = a;
    managers.put(a.username, this);
    groupsV2Manager = new GroupsV2Manager(getAccountManager().getGroupsV2Api(), a.groupsV2, accountData.profileCredentialStore, a.getUUID());
    logger.info("Created a manager for " + Util.redact(accountData.username));
  }

  public static void setDataPath(String path) {
    dataPath = path + "/data";
    attachmentsPath = path + "/attachments";
    avatarsPath = path + "/avatars";
  }

  public String getE164() { return accountData.username; }

  public UUID getUUID() { return accountData.getUUID(); }

  public SignalServiceAddress getOwnAddress() { return accountData.address.getSignalServiceAddress(); }

  public IdentityKey getIdentity() { return accountData.axolotlStore.getIdentityKeyPair().getPublicKey(); }

  public int getDeviceId() { return accountData.deviceId; }

  public String getFileName() { return Manager.getFileName(accountData.username); }

  public static String getFileName(String username) { return dataPath + "/" + username; }

  private String getMessageCachePath() { return dataPath + "/" + accountData.username + ".d/msg-cache"; }

  public static void createPrivateDirectories(String path) throws IOException {
    final Path file = new File(path).toPath();
    try {
      Set<PosixFilePermission> perms = EnumSet.of(OWNER_READ, OWNER_WRITE, OWNER_EXECUTE);
      Files.createDirectories(file, PosixFilePermissions.asFileAttribute(perms));
    } catch (UnsupportedOperationException e) {
      Files.createDirectories(file);
    }
  }

  public static boolean userExists(String username) {
    if (username == null) {
      return false;
    }
    File f = new File(Manager.getFileName(username));
    return !(!f.exists() || f.isDirectory());
  }

  public boolean hasPendingKeys() throws SQLException { return PendingAccountDataTable.getBytes(accountData.username, PendingAccountDataTable.Key.OWN_IDENTITY_KEY_PAIR) != null; }

  public boolean isRegistered() { return accountData.registered; }

  public void register(boolean voiceVerification, Optional<String> captcha) throws IOException, InvalidInputException, SQLException, NoSuchAccountException {
    accountData.password = Util.getSecret(18);

    if (voiceVerification) {
      getAccountManager().requestVoiceVerificationCode(Locale.getDefault(), captcha, Optional.absent()); // TODO: Allow requester to set the locale and challenge
    } else {
      getAccountManager().requestSmsVerificationCode(false, captcha, Optional.absent()); //  TODO: Allow requester to set challenge and androidSmsReceiverSupported
    }

    accountData.registered = false;
    accountData.init();
    accountData.save();
  }

  public SignalServiceAccountManager getAccountManager() {
    /*
SignalServiceConfiguration configuration,
                                     DynamicCredentialsProvider credentialsProvider,
                                     String signalAgent,
                                     GroupsV2Operations groupsV2Operations,
                                     boolean automaticNetworkRetry,
                                     SleepTimer timer)     */
    return new SignalServiceAccountManager(
            serviceConfiguration,
            accountData.getCredentialsProvider(),
            BuildConfig.SIGNAL_AGENT,
            GroupsUtil.GetGroupsV2Operations(serviceConfiguration),
            false,
            sleepTimer);
  }

  public static Map<String, String> getQueryMap(String query) {
    String[] params = query.split("&");
    Map<String, String> map = new HashMap<>();
    for (String param : params) {
      try {
        String name = URLDecoder.decode(param.split("=")[0], "UTF-8");
        String value = URLDecoder.decode(param.split("=")[1], "UTF-8");
        map.put(name, value);
      } catch (UnsupportedEncodingException e) {
        e.printStackTrace(); // impossible
      }
    }
    return map;
  }

  public void addDeviceLink(URI linkUri) throws IOException, InvalidKeyException, InvalidInputException {
    Map<String, String> query = getQueryMap(linkUri.getRawQuery());
    String deviceIdentifier = query.get("uuid");
    String publicKeyEncoded = query.get("pub_key");

    if (isEmpty(deviceIdentifier) || isEmpty(publicKeyEncoded)) {
      throw new RuntimeException("Invalid device link uri");
    }

    ECPublicKey deviceKey = Curve.decodePoint(Base64.decode(publicKeyEncoded), 0);

    addDevice(deviceIdentifier, deviceKey);
  }

  private void addDevice(String deviceIdentifier, ECPublicKey deviceKey) throws IOException, InvalidKeyException, InvalidInputException {
    IdentityKeyPair identityKeyPair = accountData.axolotlStore.getIdentityKeyPair();
    String verificationCode = getAccountManager().getNewDeviceVerificationCode();

    Optional<byte[]> profileKeyOptional;
    ProfileKey profileKey = accountData.getProfileKey();
    profileKeyOptional = Optional.of(profileKey.serialize());
    getAccountManager().addDevice(deviceIdentifier, deviceKey, identityKeyPair, profileKeyOptional, verificationCode);
  }

  private List<PreKeyRecord> generatePreKeys() throws IOException {
    List<PreKeyRecord> records = new LinkedList<>();

    for (int i = 0; i < PREKEY_BATCH_SIZE; i++) {
      int preKeyId = (accountData.preKeyIdOffset + i) % Medium.MAX_VALUE;
      ECKeyPair keyPair = Curve.generateKeyPair();
      PreKeyRecord record = new PreKeyRecord(preKeyId, keyPair);

      accountData.axolotlStore.storePreKey(preKeyId, record);
      records.add(record);
    }

    accountData.preKeyIdOffset = (accountData.preKeyIdOffset + PREKEY_BATCH_SIZE + 1) % Medium.MAX_VALUE;
    accountData.save();

    return records;
  }

  private SignedPreKeyRecord generateSignedPreKey(IdentityKeyPair identityKeyPair) throws IOException {
    try {
      ECKeyPair keyPair = Curve.generateKeyPair();
      byte[] signature = Curve.calculateSignature(identityKeyPair.getPrivateKey(), keyPair.getPublicKey().serialize());
      SignedPreKeyRecord record = new SignedPreKeyRecord(accountData.nextSignedPreKeyId, System.currentTimeMillis(), keyPair, signature);

      accountData.axolotlStore.storeSignedPreKey(accountData.nextSignedPreKeyId, record);
      accountData.nextSignedPreKeyId = (accountData.nextSignedPreKeyId + 1) % Medium.MAX_VALUE;
      accountData.save();

      return record;
    } catch (InvalidKeyException e) {
      throw new AssertionError(e);
    }
  }

  public void verifyAccount(String verificationCode) throws IOException, InvalidInputException, SQLException, NoSuchAccountException {
    verificationCode = verificationCode.replace("-", "");
    accountData.signalingKey = Util.getSecret(52);
    int registrationID = PendingAccountDataTable.getInt(accountData.username, PendingAccountDataTable.Key.LOCAL_REGISTRATION_ID);
    VerifyAccountResponse response = getAccountManager().verifyAccountWithCode(verificationCode, accountData.signalingKey, registrationID, true, null, null,
                                                                               accountData.getSelfUnidentifiedAccessKey(), false, SERVICE_CAPABILITIES, true);
    accountData.setUUID(UUID.fromString(response.getUuid()));
    AccountsTable.add(accountData.address.number, accountData.address.getUUID(), getFileName());
    accountData.save();

    // Once the UUID is set, load accountData fresh
    accountData = AccountData.load(new File(getFileName()));

    AccountDataTable.set(accountData.address.getUUID(), AccountDataTable.Key.LOCAL_REGISTRATION_ID, registrationID);

    byte[] identityKeyPair = PendingAccountDataTable.getBytes(accountData.username, PendingAccountDataTable.Key.LOCAL_REGISTRATION_ID);
    AccountDataTable.set(accountData.address.getUUID(), AccountDataTable.Key.OWN_IDENTITY_KEY_PAIR, identityKeyPair);

    PendingAccountDataTable.clear(accountData.username);

    accountData.registered = true;

    refreshPreKeys();
    accountData.init();
    accountData.save();
  }

  public void refreshPreKeys() throws IOException {
    List<PreKeyRecord> oneTimePreKeys = generatePreKeys();
    SignedPreKeyRecord signedPreKeyRecord = generateSignedPreKey(accountData.axolotlStore.getIdentityKeyPair());
    getAccountManager().setPreKeys(accountData.axolotlStore.getIdentityKeyPair().getPublicKey(), signedPreKeyRecord, oneTimePreKeys);
  }

  private static SignalServiceAttachmentStream createAttachment(File attachmentFile) throws IOException { return createAttachment(attachmentFile, Optional.absent()); }

  private static SignalServiceAttachmentStream createAttachment(File attachmentFile, Optional<String> caption) throws IOException {
    InputStream attachmentStream = new FileInputStream(attachmentFile);
    final long attachmentSize = attachmentFile.length();
    String mime = Files.probeContentType(attachmentFile.toPath());
    if (mime == null) {
      mime = "application/octet-stream";
    }

    /*
    InputStream inputStream,
                                       String contentType,
                                       long length,
                                       Optional<String> fileName,
                                       boolean voiceNote,
                                       boolean borderless,
                                       boolean gif,
                                       Optional<byte[]> preview,
                                       int width,
                                       int height,
                                       long uploadTimestamp,
                                       Optional<String> caption,
                                       Optional<String> blurHash,
                                       ProgressListener listener,
                                       CancelationSignal cancelationSignal,
                                       Optional<ResumableUploadSpec> resumableUploadSpec
     */
    // TODO mabybe add a parameter to set the voiceNote, preview, and caption option
    return new SignalServiceAttachmentStream(attachmentStream, mime, attachmentSize, Optional.of(attachmentFile.getName()), false, false, false, Optional.absent(), 0, 0,
                                             System.currentTimeMillis(), caption, Optional.absent(), null, null, Optional.absent());
  }

  public Optional<SignalServiceAttachmentStream> createGroupAvatarAttachment(byte[] groupId) throws IOException {
    File file = getGroupAvatarFile(groupId);
    if (!file.exists()) {
      return Optional.absent();
    }

    return Optional.of(createAttachment(file));
  }

  public Optional<SignalServiceAttachmentStream> createContactAvatarAttachment(SignalServiceAddress address) throws IOException {
    File file = getContactAvatarFile(address);
    if (!file.exists()) {
      return Optional.absent();
    }

    return Optional.of(createAttachment(file));
  }

  private GroupInfo getGroupForSending(byte[] groupId) throws GroupNotFoundException, NotAGroupMemberException {
    GroupInfo g = accountData.groupStore.getGroup(groupId);
    if (g == null) {
      throw new GroupNotFoundException(groupId);
    }

    if (!g.isMember(accountData.address)) {
      throw new NotAGroupMemberException(groupId, g.name);
    }

    return g;
  }

  public List<GroupInfo> getV1Groups() { return accountData.groupStore.getGroups(); }

  public List<JsonGroupV2Info> getGroupsV2Info() {
    List<JsonGroupV2Info> groups = new ArrayList<>();
    for (Group g : accountData.groupsV2.groups) {
      groups.add(g.getJsonGroupV2Info(this));
    }
    return groups;
  }

  public List<SendMessageResult> sendGroupV2Message(SignalServiceDataMessage.Builder message, SignalServiceGroupV2 group) throws IOException, UnknownGroupException {
    Group g = accountData.groupsV2.get(group);
    if (g.group.getDisappearingMessagesTimer() != null && g.group.getDisappearingMessagesTimer().getDuration() != 0) {
      message.withExpiration(g.group.getDisappearingMessagesTimer().getDuration());
    }

    return sendGroupV2Message(message, group, g.getMembers());
  }

  public List<SendMessageResult> sendGroupV2Message(SignalServiceDataMessage.Builder message, SignalServiceGroupV2 group, List<SignalServiceAddress> recipients)
      throws IOException {
    message.asGroupMessage(group);

    SignalServiceAddress self = accountData.address.getSignalServiceAddress();
    final List<SignalServiceAddress> membersSend = new ArrayList<>();
    for (SignalServiceAddress member : recipients) {
      if (!member.matches(self)) {
        membersSend.add(member);
      }
    }

    return sendMessage(message, membersSend);
  }

  public List<SendMessageResult> sendGroupMessage(SignalServiceDataMessage.Builder message, byte[] groupId) throws IOException, GroupNotFoundException, NotAGroupMemberException {
    if (groupId == null) {
      throw new AssertionError("Cannot send group message to null group ID");
    }
    SignalServiceGroup group = SignalServiceGroup.newBuilder(SignalServiceGroup.Type.DELIVER).withId(groupId).build();
    message.asGroupMessage(group);

    final GroupInfo g = getGroupForSending(groupId);

    if (g.messageExpirationTime != 0) {
      message.withExpiration(g.messageExpirationTime);
    }

    // Don't send group message to ourself
    SignalServiceAddress self = accountData.address.getSignalServiceAddress();
    final List<SignalServiceAddress> membersSend = new ArrayList<>();
    for (JsonAddress member : g.members) {
      if (!member.matches(self)) {
        membersSend.add(member.getSignalServiceAddress());
      }
    }

    return sendMessage(message, membersSend);
  }

  public List<SendMessageResult> sendQuitGroupMessage(byte[] groupId) throws GroupNotFoundException, IOException, NotAGroupMemberException {
    SignalServiceGroup group = SignalServiceGroup.newBuilder(SignalServiceGroup.Type.QUIT).withId(groupId).build();

    SignalServiceDataMessage.Builder messageBuilder = SignalServiceDataMessage.newBuilder().asGroupMessage(group);

    final GroupInfo g = getGroupForSending(groupId);
    g.members.remove(accountData.address);
    accountData.groupStore.updateGroup(g);

    return sendMessage(messageBuilder, g.getMembers());
  }

  public GroupInfo sendUpdateGroupMessage(byte[] groupId, String name, Collection<SignalServiceAddress> members, String avatarFile)
      throws IOException, GroupNotFoundException, NotAGroupMemberException {
    GroupInfo g;
    if (groupId == null) {
      // Create new group
      g = new GroupInfo(Util.getSecretBytes(16));
      g.addMember(accountData.address);
    } else {
      g = getGroupForSending(groupId);
    }

    if (name != null) {
      g.name = name;
    }

    if (members != null) {
      for (SignalServiceAddress member : members) {
        for (JsonAddress m : g.members) {
          if (m.matches(member)) {
            continue;
          }
          g.addMember(new JsonAddress(member));
        }
      }
    }

    if (avatarFile != null) {
      createPrivateDirectories(avatarsPath);
      File aFile = getGroupAvatarFile(g.groupId);
      Files.copy(Paths.get(avatarFile), aFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
    }

    accountData.groupStore.updateGroup(g);

    SignalServiceDataMessage.Builder messageBuilder = getGroupUpdateMessageBuilder(g);

    // Don't send group message to ourself
    final List<SignalServiceAddress> membersSend = g.getMembers();
    membersSend.remove(accountData.address.getSignalServiceAddress());
    sendMessage(messageBuilder, membersSend);
    return g;
  }

  public SignalServiceDataMessage.Builder getGroupUpdateMessageBuilder(GroupInfo g) {
    SignalServiceGroup.Builder group = SignalServiceGroup.newBuilder(SignalServiceGroup.Type.UPDATE).withId(g.groupId).withName(g.name).withMembers(g.getMembers());

    File aFile = getGroupAvatarFile(g.groupId);
    if (aFile.exists()) {
      try {
        group.withAvatar(createAttachment(aFile));
      } catch (IOException e) {
        logger.warn("Unable to attach group avatar:" + aFile.toString(), e);
      }
    }

    return SignalServiceDataMessage.newBuilder().asGroupMessage(group.build());
  }

  public List<SendMessageResult> setExpiration(byte[] groupId, int expiresInSeconds) throws IOException, GroupNotFoundException, NotAGroupMemberException {
    if (groupId == null) {
      return null;
    }
    GroupInfo g = getGroupForSending(groupId);
    g.messageExpirationTime = expiresInSeconds;
    accountData.groupStore.updateGroup(g);
    accountData.save();
    SignalServiceDataMessage.Builder messageBuilder = getGroupUpdateMessageBuilder(g);
    messageBuilder.asExpirationUpdate().withExpiration(expiresInSeconds);
    return sendMessage(messageBuilder, g.getMembers());
  }

  public List<SendMessageResult> setExpiration(SignalServiceAddress address, int expiresInSeconds) throws IOException {
    ContactStore.ContactInfo contact = accountData.contactStore.getContact(address);
    contact.messageExpirationTime = expiresInSeconds;
    accountData.contactStore.updateContact(contact);
    SignalServiceDataMessage.Builder messageBuilder = SignalServiceDataMessage.newBuilder().asExpirationUpdate().withExpiration(expiresInSeconds);
    List<SignalServiceAddress> recipients = new ArrayList<>(1);
    recipients.add(address);
    return sendMessage(messageBuilder, recipients);
  }

  private List<SendMessageResult> sendGroupInfoRequest(byte[] groupId, SignalServiceAddress recipient) throws IOException {
    if (groupId == null) {
      return null;
    }

    SignalServiceGroup.Builder group = SignalServiceGroup.newBuilder(SignalServiceGroup.Type.REQUEST_INFO).withId(groupId);

    SignalServiceDataMessage.Builder messageBuilder = SignalServiceDataMessage.newBuilder().asGroupMessage(group.build());

    // Send group info request message to the recipient who sent us a message with this groupId
    final List<SignalServiceAddress> membersSend = new ArrayList<>();
    membersSend.add(recipient);
    return sendMessage(messageBuilder, membersSend);
  }

  public ContactStore.ContactInfo updateContact(ContactStore.ContactInfo contact) throws IOException {
    contact.address = getResolver().resolve(contact.address);
    ContactStore.ContactInfo c = accountData.contactStore.updateContact(contact);
    accountData.save();
    return c;
  }

  public GroupInfo updateGroup(byte[] groupId, String name, List<String> stringMembers, String avatar) throws IOException, GroupNotFoundException, NotAGroupMemberException {
    if (groupId.length == 0) {
      groupId = null;
    }
    if (name.isEmpty()) {
      name = null;
    }
    if (avatar.isEmpty()) {
      avatar = null;
    }
    List<SignalServiceAddress> members = stringMembers.stream().map(x -> new SignalServiceAddress(null, x)).collect(Collectors.toList());
    return sendUpdateGroupMessage(groupId, name, members, avatar);
  }

  public void requestSyncGroups() throws IOException {
    SignalServiceProtos.SyncMessage.Request r = SignalServiceProtos.SyncMessage.Request.newBuilder().setType(SignalServiceProtos.SyncMessage.Request.Type.GROUPS).build();
    SignalServiceSyncMessage message = SignalServiceSyncMessage.forRequest(new RequestMessage(r));
    try {
      sendSyncMessage(message);
    } catch (org.whispersystems.signalservice.api.crypto.UntrustedIdentityException e) {
      logger.catching(e);
    }
  }

  public void requestSyncContacts() throws IOException {
    SignalServiceProtos.SyncMessage.Request r = SignalServiceProtos.SyncMessage.Request.newBuilder().setType(SignalServiceProtos.SyncMessage.Request.Type.CONTACTS).build();
    SignalServiceSyncMessage message = SignalServiceSyncMessage.forRequest(new RequestMessage(r));
    try {
      sendSyncMessage(message);
    } catch (org.whispersystems.signalservice.api.crypto.UntrustedIdentityException e) {
      logger.catching(e);
    }
  }

  public void requestSyncConfiguration() throws IOException {
    SignalServiceProtos.SyncMessage.Request r = SignalServiceProtos.SyncMessage.Request.newBuilder().setType(SignalServiceProtos.SyncMessage.Request.Type.CONFIGURATION).build();
    SignalServiceSyncMessage message = SignalServiceSyncMessage.forRequest(new RequestMessage(r));
    try {
      sendSyncMessage(message);
    } catch (org.whispersystems.signalservice.api.crypto.UntrustedIdentityException e) {
      logger.catching(e);
    }
  }

  public void requestSyncBlocked() throws IOException, org.whispersystems.signalservice.api.crypto.UntrustedIdentityException {
    SignalServiceProtos.SyncMessage.Request r = SignalServiceProtos.SyncMessage.Request.newBuilder().setType(SignalServiceProtos.SyncMessage.Request.Type.BLOCKED).build();
    SignalServiceSyncMessage message = SignalServiceSyncMessage.forRequest(new RequestMessage(r));
    sendSyncMessage(message);
  }

  public void sendSyncMessage(SignalServiceSyncMessage message) throws IOException, org.whispersystems.signalservice.api.crypto.UntrustedIdentityException {
    SignalServiceMessageSender messageSender = getMessageSender();
    try {
      messageSender.sendSyncMessage(message, Optional.absent());
    } catch (org.whispersystems.signalservice.api.crypto.UntrustedIdentityException e) {
      accountData.axolotlStore.saveIdentity(e.getIdentifier(), e.getIdentityKey(), TrustLevel.UNTRUSTED);
      throw e;
    }
  }

  public SendMessageResult sendTypingMessage(SignalServiceTypingMessage message, SignalServiceAddress address) throws IOException {
    if (address == null) {
      accountData.save();
      return null;
    }

    address = accountData.getResolver().resolve(address);

    SignalServiceMessageSender messageSender = getMessageSender();

    try {
      // TODO: this just calls sendMessage() under the hood. We should call sendMessage() directly so we can get the return value
      messageSender.sendTyping(address, getAccessFor(address), message);
      return null;
    } catch (org.whispersystems.signalservice.api.crypto.UntrustedIdentityException e) {
      accountData.axolotlStore.saveIdentity(e.getIdentifier(), e.getIdentityKey(), TrustLevel.UNTRUSTED);
      return SendMessageResult.identityFailure(address, e.getIdentityKey());
    }
  }

  public SendMessageResult sendReceipt(SignalServiceReceiptMessage message, SignalServiceAddress address) throws IOException {
    if (address == null) {
      accountData.save();
      return null;
    }

    address = accountData.getResolver().resolve(address);

    SignalServiceMessageSender messageSender = getMessageSender();

    try {
      // TODO: this just calls sendMessage() under the hood. We should call sendMessage() directly so we can get the return value
      messageSender.sendReceipt(address, getAccessFor(address), message);
      if (message.getType() == SignalServiceReceiptMessage.Type.READ) {
        List<ReadMessage> readMessages = new LinkedList<>();
        for (Long ts : message.getTimestamps()) {
          readMessages.add(new ReadMessage(address, ts));
        }
        messageSender.sendSyncMessage(SignalServiceSyncMessage.forRead(readMessages), Optional.absent());
      }
      return null;
    } catch (org.whispersystems.signalservice.api.crypto.UntrustedIdentityException e) {
      accountData.axolotlStore.saveIdentity(e.getIdentifier(), e.getIdentityKey(), TrustLevel.UNTRUSTED);
      return SendMessageResult.identityFailure(address, e.getIdentityKey());
    }
  }

  public List<SendMessageResult> sendMessage(SignalServiceDataMessage.Builder messageBuilder, Collection<SignalServiceAddress> recipients) throws IOException {
    if (recipients == null) {
      accountData.save();
      return Collections.emptyList();
    }

    recipients = accountData.getResolver().resolve(recipients);

    try {
      ProfileAndCredentialEntry profile = getRecipientProfileKeyCredential(getOwnAddress());
      if (profile.getProfile() != null) {
        messageBuilder.withProfileKey(profile.getProfileKey().serialize());
      }
    } catch (InterruptedException | ExecutionException | TimeoutException e) {
      logger.warn("Failed to get own profile key");
      e.printStackTrace();
    }

    SignalServiceDataMessage message = null;
    try {
      SignalServiceMessageSender messageSender = getMessageSender();
      message = messageBuilder.build();

      if (message.getGroupContext().isPresent()) {
        try {
          final boolean isRecipientUpdate = false;
          /*
          List<SignalServiceAddress>             recipients,
                                                 List<Optional<UnidentifiedAccessPair>> unidentifiedAccess,
                                                 boolean                                isRecipientUpdate,
                                                 ContentHint                            contentHint,
                                                 SignalServiceDataMessage
           */

          List<SignalServiceAddress> recipientList = new ArrayList<>(recipients);
          List<SendMessageResult> result = messageSender.sendDataMessage(recipientList, getAccessFor(recipients), isRecipientUpdate, ContentHint.DEFAULT, message);
          for (SendMessageResult r : result) {
            if (r.getIdentityFailure() != null) {
              accountData.axolotlStore.saveIdentity(r.getAddress(), r.getIdentityFailure().getIdentityKey(), TrustLevel.UNTRUSTED);
            }
          }
          return result;
        } catch (org.whispersystems.signalservice.api.crypto.UntrustedIdentityException e) {
          accountData.axolotlStore.saveIdentity(e.getIdentifier(), e.getIdentityKey(), TrustLevel.UNTRUSTED);
          return Collections.emptyList();
        }
      } else if (recipients.size() == 1 && recipients.contains(accountData.address.getSignalServiceAddress())) {
        SignalServiceAddress recipient = accountData.address.getSignalServiceAddress();
        final Optional<UnidentifiedAccessPair> unidentifiedAccess = getAccessFor(recipient);
        SentTranscriptMessage transcript = new SentTranscriptMessage(Optional.of(recipient), message.getTimestamp(), message, message.getExpiresInSeconds(),
                                                                     Collections.singletonMap(recipient, unidentifiedAccess.isPresent()), false);
        SignalServiceSyncMessage syncMessage = SignalServiceSyncMessage.forSentTranscript(transcript);

        List<SendMessageResult> results = new ArrayList<>(recipients.size());
        try {
          messageSender.sendSyncMessage(syncMessage, unidentifiedAccess);
        } catch (org.whispersystems.signalservice.api.crypto.UntrustedIdentityException e) {
          accountData.axolotlStore.saveIdentity(e.getIdentifier(), e.getIdentityKey(), TrustLevel.UNTRUSTED);
          results.add(SendMessageResult.identityFailure(recipient, e.getIdentityKey()));
        }
        return results;
      } else {
        // Send to all individually, so sync messages are sent correctly
        List<SendMessageResult> results = new ArrayList<>(recipients.size());
        for (SignalServiceAddress address : recipients) {
          ContactStore.ContactInfo contact = accountData.contactStore.getContact(address);
          messageBuilder.withExpiration(contact.messageExpirationTime);
          message = messageBuilder.build();
          try {
            if (accountData.address.matches(address)) {
              SignalServiceAddress recipient = accountData.address.getSignalServiceAddress();

              final Optional<UnidentifiedAccessPair> unidentifiedAccess = getAccessFor(recipient);
              SentTranscriptMessage transcript = new SentTranscriptMessage(Optional.of(recipient), message.getTimestamp(), message, message.getExpiresInSeconds(),
                                                                           Collections.singletonMap(recipient, unidentifiedAccess.isPresent()), false);
              SignalServiceSyncMessage syncMessage = SignalServiceSyncMessage.forSentTranscript(transcript);
              long start = System.currentTimeMillis();
              messageSender.sendSyncMessage(syncMessage, unidentifiedAccess);
              results.add(SendMessageResult.success(recipient, unidentifiedAccess.isPresent(), false, System.currentTimeMillis() - start));
            } else {
              results.add(messageSender.sendDataMessage(address, getAccessFor(address), ContentHint.DEFAULT, message));
            }
          } catch (org.whispersystems.signalservice.api.crypto.UntrustedIdentityException e) {
            if (e.getIdentityKey() != null) {
              accountData.axolotlStore.saveIdentity(e.getIdentifier(), e.getIdentityKey(), TrustLevel.UNTRUSTED);
            }
            results.add(SendMessageResult.identityFailure(address, e.getIdentityKey()));
          }
        }
        return results;
      }
    } finally {
      if (message != null && message.isEndSession()) {
        for (SignalServiceAddress recipient : recipients) {
          handleEndSession(recipient);
        }
      }
    }
  }

  private static CertificateValidator getCertificateValidator() {
    try {
      ECPublicKey unidentifiedSenderTrustRoot = Curve.decodePoint(Base64.decode(BuildConfig.UNIDENTIFIED_SENDER_TRUST_ROOT), 0);
      return new CertificateValidator(unidentifiedSenderTrustRoot);
    } catch (InvalidKeyException | IOException e) {
      throw new AssertionError(e);
    }
  }

  private SignalServiceContent decryptMessage(SignalServiceEnvelope envelope)
      throws InvalidMetadataMessageException, InvalidMetadataVersionException, ProtocolInvalidKeyIdException, ProtocolUntrustedIdentityException, ProtocolLegacyMessageException,
             ProtocolNoSessionException, ProtocolInvalidVersionException, ProtocolInvalidMessageException, ProtocolInvalidKeyException, ProtocolDuplicateMessageException,
             SelfSendException, UnsupportedDataMessageException, org.whispersystems.libsignal.UntrustedIdentityException {
    SignalServiceCipher cipher =
        new SignalServiceCipher(accountData.address.getSignalServiceAddress(), accountData.axolotlStore, new SessionLock(accountData.getUUID()), getCertificateValidator());
    try {
      return cipher.decrypt(envelope);
    } catch (ProtocolUntrustedIdentityException e) {
      if (e.getCause() instanceof org.whispersystems.libsignal.UntrustedIdentityException) {
        org.whispersystems.libsignal.UntrustedIdentityException identityException = (org.whispersystems.libsignal.UntrustedIdentityException)e.getCause();
        accountData.axolotlStore.saveIdentity(identityException.getName(), identityException.getUntrustedIdentity(), TrustLevel.UNTRUSTED);
        throw identityException;
      }
      throw e;
    }
  }

  private void handleEndSession(SignalServiceAddress address) { accountData.axolotlStore.deleteAllSessions(address); }

  public List<SendMessageResult> send(SignalServiceDataMessage.Builder messageBuilder, JsonAddress recipientAddress, String recipientGroupId)
      throws GroupNotFoundException, NotAGroupMemberException, IOException, InvalidRecipientException, UnknownGroupException {
    if (recipientGroupId != null && recipientAddress == null) {
      if (recipientGroupId.length() == 24) { // redirect to new group if it exists
        recipientGroupId = accountData.getMigratedGroupId(recipientGroupId);
      }
      if (recipientGroupId.length() == 44) {
        Group group = accountData.groupsV2.get(recipientGroupId);
        if (group == null) {
          throw new GroupNotFoundException("Unknown group requested");
        }
        return sendGroupV2Message(messageBuilder, group.getSignalServiceGroupV2());
      } else {
        byte[] groupId = Base64.decode(recipientGroupId);
        return sendGroupMessage(messageBuilder, groupId);
      }
    } else if (recipientAddress != null && recipientGroupId == null) {
      List<SignalServiceAddress> r = new ArrayList<>();
      r.add(recipientAddress.getSignalServiceAddress());
      return sendMessage(messageBuilder, r);
    } else {
      throw new InvalidRecipientException();
    }
  }

  public interface ReceiveMessageHandler { void handleMessage(SignalServiceEnvelope envelope, SignalServiceContent decryptedContent, Throwable e); }

  private List<Job> handleSignalServiceDataMessage(SignalServiceDataMessage message, boolean isSync, SignalServiceAddress source, SignalServiceAddress destination,
                                                   boolean ignoreAttachments) throws MissingConfigurationException, IOException, VerificationFailedException {

    List<Job> jobs = new ArrayList<>();
    if (message.getGroupContext().isPresent()) {
      SignalServiceGroup groupInfo;
      SignalServiceGroupContext groupContext = message.getGroupContext().get();

      if (groupContext.getGroupV2().isPresent()) {
        if (groupsV2Manager.handleIncomingDataMessage(message)) {
          accountData.save();
        }
      }

      if (groupContext.getGroupV1().isPresent()) {
        groupInfo = groupContext.getGroupV1().get();
        GroupInfo group = accountData.groupStore.getGroup(groupInfo.getGroupId());

        if (message.isExpirationUpdate()) {
          if (group.messageExpirationTime != message.getExpiresInSeconds()) {
            group.messageExpirationTime = message.getExpiresInSeconds();
          }
          accountData.groupStore.updateGroup(group);
          accountData.save();
        }

        switch (groupInfo.getType()) {
        case UPDATE:
          if (group == null) {
            group = new GroupInfo(groupInfo.getGroupId());
          }

          if (groupInfo.getAvatar().isPresent()) {
            SignalServiceAttachment avatar = groupInfo.getAvatar().get();
            if (avatar.isPointer()) {
              try {
                retrieveGroupAvatarAttachment(avatar.asPointer(), group.groupId);
              } catch (IOException | InvalidMessageException e) {
                logger.warn("Failed to retrieve group avatar (" + avatar.asPointer().getRemoteId() + "): " + e.getMessage());
              }
            }
          }

          if (groupInfo.getName().isPresent()) {
            group.name = groupInfo.getName().get();
          }

          if (groupInfo.getMembers().isPresent()) {
            AddressResolver resolver = accountData.getResolver();
            Set<SignalServiceAddress> members = groupInfo.getMembers().get().stream().map(resolver::resolve).collect(Collectors.toSet());
            group.addMembers(members);
          }

          accountData.groupStore.updateGroup(group);
          break;
        case DELIVER:
          if (group == null) {
            try {
              sendGroupInfoRequest(groupInfo.getGroupId(), source);
            } catch (IOException e) {
              logger.catching(e);
            }
          }
          break;
        case QUIT:
          if (group == null) {
            try {
              sendGroupInfoRequest(groupInfo.getGroupId(), source);
            } catch (IOException e) {
              logger.catching(e);
            }
          } else {
            group.removeMember(source);
            accountData.groupStore.updateGroup(group);
          }
          break;
        case REQUEST_INFO:
          if (group != null) {
            jobs.add(new SendLegacyGroupUpdateJob(this, groupInfo.getGroupId(), source));
          }
          break;
        }
      }
    } else {
      ContactStore.ContactInfo c = accountData.contactStore.getContact(isSync ? destination : source);
      c.messageExpirationTime = message.getExpiresInSeconds();
      accountData.contactStore.updateContact(c);
    }

    if (message.isEndSession()) {
      handleEndSession(isSync ? destination : source);
    }

    if (message.getAttachments().isPresent() && !ignoreAttachments) {
      for (SignalServiceAttachment attachment : message.getAttachments().get()) {
        if (attachment.isPointer()) {
          try {
            retrieveAttachment(attachment.asPointer());
          } catch (IOException | InvalidMessageException e) {
            logger.warn("Failed to retrieve attachment (" + attachment.asPointer().getRemoteId() + "): " + e.getMessage());
          }
        }
      }
    }

    if (message.getProfileKey().isPresent() && message.getProfileKey().get().length == 32) {
      final ProfileKey profileKey;
      try {
        profileKey = new ProfileKey(message.getProfileKey().get());
      } catch (InvalidInputException e) {
        throw new AssertionError(e);
      }
      accountData.profileCredentialStore.storeProfileKey(source, profileKey);
    }
    return jobs;
  }

  public void retryFailedReceivedMessages(ReceiveMessageHandler handler, boolean ignoreAttachments) throws IOException, MissingConfigurationException, SQLException {
    final File cachePath = new File(getMessageCachePath());
    if (!cachePath.exists()) {
      return;
    }
    for (final File dir : cachePath.listFiles()) {
      if (!dir.isDirectory()) {
        continue;
      }

      for (final File fileEntry : dir.listFiles()) {
        if (!fileEntry.isFile()) {
          continue;
        }
        SignalServiceEnvelope envelope;
        try {
          envelope = loadEnvelope(fileEntry);
          if (envelope == null) {
            continue;
          }
        } catch (IOException e) {
          Files.delete(fileEntry.toPath());
          logger.catching(e);
          continue;
        }
        SignalServiceContent content = null;
        Exception exception = null;
        if (!envelope.isReceipt()) {
          try {
            content = decryptMessage(envelope);
          } catch (Exception e) {
            exception = e;
          }
          if (exception == null) {
            try {
              handleMessage(envelope, content, ignoreAttachments);
            } catch (VerificationFailedException e) {
              logger.catching(e);
            }
          }
        }
        accountData.save();
        handler.handleMessage(envelope, content, exception);
        try {
          Files.delete(fileEntry.toPath());
        } catch (IOException e) {
          logger.warn("Failed to delete cached message file “" + fileEntry + "”: " + e.getMessage());
        }
      }
      // Try to delete directory if empty
      dir.delete();
    }

    while (true) {
      StoredEnvelope storedEnvelope = accountData.getDatabase().getMessageQueueTable().nextEnvelope();
      if (storedEnvelope == null) {
        break;
      }
      SignalServiceEnvelope envelope = storedEnvelope.envelope;

      try {
        SignalServiceContent content = null;
        Exception exception = null;
        if (!envelope.isReceipt()) {
          try {
            content = decryptMessage(envelope);
          } catch (Exception e) {
            exception = e;
          }
          if (exception == null) {
            try {
              handleMessage(envelope, content, ignoreAttachments);
            } catch (VerificationFailedException e) {
              logger.catching(e);
            }
          }
        }
        accountData.save();
        handler.handleMessage(envelope, content, exception);
      } finally {
        accountData.getDatabase().getMessageQueueTable().deleteEnvelope(storedEnvelope.databaseId);
      }
    }
  }

  public void shutdownMessagePipe() {
    if (messagePipe != null) {
      messagePipe.shutdown();
    }
  }

  public void receiveMessages(long timeout, TimeUnit unit, boolean returnOnTimeout, boolean ignoreAttachments, ReceiveMessageHandler handler)
      throws IOException, MissingConfigurationException, VerificationFailedException, SQLException {
    retryFailedReceivedMessages(handler, ignoreAttachments);
    accountData.saveIfNeeded();

    final SignalServiceMessageReceiver messageReceiver = getMessageReceiver();

    try {
      if (messagePipe == null) {
        messagePipe = messageReceiver.createMessagePipe();
      }

      while (true) {
        SignalServiceEnvelope envelope;
        SignalServiceContent content = null;
        Exception exception = null;
        MutableLong databaseId = new MutableLong();
        try {
          envelope = messagePipe.read(timeout, unit, new SignalServiceMessagePipe.MessagePipeCallback() {
            @Override
            public void onMessage(SignalServiceEnvelope envelope) {
              // store message on disk, before acknowledging receipt to the server
              try {
                long id = accountData.getDatabase().getMessageQueueTable().storeEnvelope(envelope);
                databaseId.setValue(id);
              } catch (SQLException e) {
                logger.warn("Failed to store encrypted message in sqlite cache, ignoring: " + e.getMessage());
              }
            }
          });
        } catch (TimeoutException e) {
          if (returnOnTimeout)
            return;
          continue;
        } catch (InvalidVersionException e) {
          logger.info("Ignoring error: " + e.getMessage());
          continue;
        }
        if (envelope.hasSource()) {
          // Store uuid if we don't have it already
          accountData.getResolver().resolve(envelope.getSourceAddress());
        }
        if (!envelope.isReceipt()) {
          try {
            content = decryptMessage(envelope);
          } catch (Exception e) {
            exception = e;
          }
          if (exception == null) {
            handleMessage(envelope, content, ignoreAttachments);
          }
        }
        handler.handleMessage(envelope, content, exception);
        try {
          Long id = databaseId.getValue();
          if (id != null) {
            accountData.getDatabase().getMessageQueueTable().deleteEnvelope(id);
          }
        } catch (SQLException e) {
          logger.error("failed to remove cached message from database");
        }
      }
    } finally {
      if (messagePipe != null) {
        messagePipe.shutdown();
        messagePipe = null;
      }
      accountData.save();
    }
  }

  private void handleMessage(SignalServiceEnvelope envelope, SignalServiceContent content, boolean ignoreAttachments)
      throws IOException, MissingConfigurationException, VerificationFailedException {
    List<Job> jobs = new ArrayList<>();
    if (content == null) {
      return;
    }
    SignalServiceAddress source = envelope.hasSource() ? envelope.getSourceAddress() : content.getSender();
    AddressResolver resolver = accountData.getResolver();
    resolver.resolve(source);
    if (content.getDataMessage().isPresent()) {
      if (content.isNeedsReceipt()) {
        SignalServiceAddress sender = envelope.isUnidentifiedSender() && envelope.hasSource() ? envelope.getSourceAddress() : content.getSender();
        jobs.add(new SendDeliveryReceiptJob(this, sender, content.getTimestamp()));
      }
      SignalServiceDataMessage message = content.getDataMessage().get();
      jobs.addAll(handleSignalServiceDataMessage(message, false, source, accountData.address.getSignalServiceAddress(), ignoreAttachments));
    }

    if (envelope.isPreKeySignalMessage()) {
      jobs.add(new RefreshPreKeysJob(getUUID()));
    }

    if (content.getSyncMessage().isPresent()) {
      SignalServiceSyncMessage syncMessage = content.getSyncMessage().get();
      if (syncMessage.getSent().isPresent()) {
        SignalServiceDataMessage message = syncMessage.getSent().get().getMessage();
        jobs.addAll(handleSignalServiceDataMessage(message, true, source, syncMessage.getSent().get().getDestination().orNull(), ignoreAttachments));
      }
      if (syncMessage.getRequest().isPresent()) {
        RequestMessage rm = syncMessage.getRequest().get();
        if (rm.isContactsRequest()) {
          jobs.add(new SendContactsSyncJob(this));
        }
        if (rm.isGroupsRequest()) {
          jobs.add(new SendGroupSyncJob(this));
        }
      }

      if (syncMessage.getGroups().isPresent()) {
        File tmpFile = null;
        try {
          tmpFile = Util.createTempFile();
          try (InputStream attachmentAsStream = retrieveAttachmentAsStream(syncMessage.getGroups().get().asPointer(), tmpFile)) {
            DeviceGroupsInputStream s = new DeviceGroupsInputStream(attachmentAsStream);
            DeviceGroup g;
            logger.debug("Sync message included new groups!");
            while ((g = s.read()) != null) {
              accountData.groupStore.updateGroup(new GroupInfo(g));
              if (g.getAvatar().isPresent()) {
                retrieveGroupAvatarAttachment(g.getAvatar().get(), g.getId());
              }
              g.getMembers().stream().map(resolver::resolve);
            }
          }
        } catch (Exception e) {
          logger.catching(e);
        } finally {
          if (tmpFile != null) {
            try {
              Files.delete(tmpFile.toPath());
            } catch (IOException e) {
              logger.warn("Failed to delete received groups temp file “" + tmpFile + "”: " + e.getMessage());
            }
          }
        }
        if (syncMessage.getBlockedList().isPresent()) {
          // TODO store list of blocked numbers
        }
      }
      if (syncMessage.getContacts().isPresent()) {
        File tmpFile = null;
        try {
          tmpFile = Util.createTempFile();
          final ContactsMessage contactsMessage = syncMessage.getContacts().get();
          try (InputStream attachmentAsStream = retrieveAttachmentAsStream(contactsMessage.getContactsStream().asPointer(), tmpFile)) {
            DeviceContactsInputStream s = new DeviceContactsInputStream(attachmentAsStream);
            if (contactsMessage.isComplete()) {
              accountData.contactStore.clear();
            }
            DeviceContact c;
            while ((c = s.read()) != null) {
              ContactStore.ContactInfo contact = accountData.contactStore.getContact(resolver.resolve(c.getAddress()));
              contact.update(c);
              updateContact(contact);
              if (c.getAvatar().isPresent()) {
                retrieveContactAvatarAttachment(c.getAvatar().get(), contact.address.getSignalServiceAddress());
              }
              if (c.getProfileKey().isPresent()) {
                accountData.profileCredentialStore.storeProfileKey(c.getAddress(), c.getProfileKey().get());
              }
            }
          }
        } catch (Exception e) {
          logger.catching(e);
        } finally {
          if (tmpFile != null) {
            try {
              Files.delete(tmpFile.toPath());
            } catch (IOException e) {
              logger.warn("Failed to delete received contacts temp file “" + tmpFile + "”: " + e.getMessage());
            }
          }
        }
      }
      if (syncMessage.getVerified().isPresent()) {
        final VerifiedMessage verifiedMessage = syncMessage.getVerified().get();
        SignalServiceAddress destination = resolver.resolve(verifiedMessage.getDestination());
        TrustLevel trustLevel = TrustLevel.fromVerifiedState(verifiedMessage.getVerified());
        accountData.axolotlStore.saveIdentity(destination, verifiedMessage.getIdentityKey(), trustLevel);
      }
    }
    for (Job job : jobs) {
      try {
        logger.debug("running " + job.getClass().getName());
        job.run();
      } catch (Throwable t) {
        logger.warn("Error running " + job.getClass().getName());
        logger.catching(t);
      }
    }
  }

  private SignalServiceEnvelope loadEnvelope(File file) throws IOException {
    logger.debug("Loading cached envelope from " + file.toString());
    try (FileInputStream f = new FileInputStream(file)) {
      DataInputStream in = new DataInputStream(f);
      int version = in.readInt();
      if (version > 4) {
        return null;
      }
      int type = in.readInt();
      String source = in.readUTF();
      UUID sourceUuid = null;
      if (version >= 3) {
        sourceUuid = UuidUtil.parseOrNull(in.readUTF());
      }
      int sourceDevice = in.readInt();
      if (version == 1) {
        // read legacy relay field
        in.readUTF();
      }
      long timestamp = in.readLong();
      byte[] content = null;
      int contentLen = in.readInt();
      if (contentLen > 0) {
        content = new byte[contentLen];
        in.readFully(content);
      }
      byte[] legacyMessage = null;
      int legacyMessageLen = in.readInt();
      if (legacyMessageLen > 0) {
        legacyMessage = new byte[legacyMessageLen];
        in.readFully(legacyMessage);
      }
      long serverReceivedTimestamp = 0;
      String uuid = null;
      if (version >= 2) {
        serverReceivedTimestamp = in.readLong();
        uuid = in.readUTF();
        if ("".equals(uuid)) {
          uuid = null;
        }
      }
      long serverDeliveredTimestamp = 0;
      if (version >= 4) {
        serverDeliveredTimestamp = in.readLong();
      }
      Optional<SignalServiceAddress> sourceAddress = sourceUuid == null && source.isEmpty() ? Optional.absent() : Optional.of(new SignalServiceAddress(sourceUuid, source));
      return new SignalServiceEnvelope(type, sourceAddress, sourceDevice, timestamp, legacyMessage, content, serverReceivedTimestamp, serverDeliveredTimestamp, uuid);
    }
  }

  public File getContactAvatarFile(SignalServiceAddress address) { return new File(avatarsPath, "contact-" + address.getNumber().get()); }

  public File getProfileAvatarFile(SignalServiceAddress address) {
    if (!address.getUuid().isPresent()) {
      return null;
    }
    return new File(avatarsPath, address.getUuid().get().toString());
  }

  private File retrieveContactAvatarAttachment(SignalServiceAttachment attachment, SignalServiceAddress address)
      throws IOException, InvalidMessageException, MissingConfigurationException {
    createPrivateDirectories(avatarsPath);
    if (attachment.isPointer()) {
      SignalServiceAttachmentPointer pointer = attachment.asPointer();
      return retrieveAttachment(pointer, getContactAvatarFile(address), false);
    } else {
      SignalServiceAttachmentStream stream = attachment.asStream();
      return retrieveAttachment(stream, getContactAvatarFile(address));
    }
  }

  public File getGroupAvatarFile(byte[] groupId) { return new File(avatarsPath, "group-" + Base64.encodeBytes(groupId).replace("/", "_")); }

  private File retrieveGroupAvatarAttachment(SignalServiceAttachment attachment, byte[] groupId) throws IOException, InvalidMessageException, MissingConfigurationException {
    createPrivateDirectories(avatarsPath);
    if (attachment.isPointer()) {
      SignalServiceAttachmentPointer pointer = attachment.asPointer();
      return retrieveAttachment(pointer, getGroupAvatarFile(groupId), false);
    } else {
      SignalServiceAttachmentStream stream = attachment.asStream();
      return retrieveAttachment(stream, getGroupAvatarFile(groupId));
    }
  }

  public File getAttachmentFile(String attachmentId) { return new File(attachmentsPath, attachmentId); }

  private File retrieveAttachment(SignalServiceAttachmentPointer pointer) throws IOException, InvalidMessageException, MissingConfigurationException {
    createPrivateDirectories(attachmentsPath);
    return retrieveAttachment(pointer, getAttachmentFile(pointer.getRemoteId().toString()), true);
  }

  private File retrieveAttachment(SignalServiceAttachmentStream stream, File outputFile) throws IOException {
    InputStream input = stream.getInputStream();

    try (OutputStream output = new FileOutputStream(outputFile)) {
      byte[] buffer = new byte[4096];
      int read;

      while ((read = input.read(buffer)) != -1) {
        output.write(buffer, 0, read);
      }
    } catch (FileNotFoundException e) {
      logger.catching(e);
      return null;
    }
    return outputFile;
  }

  private File retrieveAttachment(SignalServiceAttachmentPointer pointer, File outputFile, boolean storePreview)
      throws IOException, InvalidMessageException, MissingConfigurationException {
    if (storePreview && pointer.getPreview().isPresent()) {
      File previewFile = new File(outputFile + ".preview");
      try (OutputStream output = new FileOutputStream(previewFile)) {
        byte[] preview = pointer.getPreview().get();
        output.write(preview, 0, preview.length);
      } catch (FileNotFoundException e) {
        logger.catching(e);
        return null;
      }
    }

    final SignalServiceMessageReceiver messageReceiver = getMessageReceiver();

    File tmpFile = Util.createTempFile();
    try (InputStream input = messageReceiver.retrieveAttachment(pointer, tmpFile, MAX_ATTACHMENT_SIZE)) {
      try (OutputStream output = new FileOutputStream(outputFile)) {
        byte[] buffer = new byte[4096];
        int read;

        while ((read = input.read(buffer)) != -1) {
          output.write(buffer, 0, read);
        }
      } catch (FileNotFoundException e) {
        logger.catching(e);
        return null;
      }
    } finally {
      try {
        Files.delete(tmpFile.toPath());
      } catch (IOException e) {
        logger.warn("Failed to delete received attachment temp file “" + tmpFile + "”: " + e.getMessage());
      }
    }
    return outputFile;
  }

  private InputStream retrieveAttachmentAsStream(SignalServiceAttachmentPointer pointer, File tmpFile) throws IOException, InvalidMessageException, MissingConfigurationException {
    final SignalServiceMessageReceiver messageReceiver = getMessageReceiver();
    return messageReceiver.retrieveAttachment(pointer, tmpFile, MAX_ATTACHMENT_SIZE);
  }

  private void sendVerifiedMessage(SignalServiceAddress destination, IdentityKey identityKey, TrustLevel trustLevel)
      throws IOException, org.whispersystems.signalservice.api.crypto.UntrustedIdentityException {
    VerifiedMessage verifiedMessage = new VerifiedMessage(destination, identityKey, trustLevel.toVerifiedState(), System.currentTimeMillis());
    sendSyncMessage(SignalServiceSyncMessage.forVerified(verifiedMessage));
  }

  public List<ContactStore.ContactInfo> getContacts() {
    if (accountData.contactStore == null) {
      return Collections.emptyList();
    }
    return this.accountData.contactStore.getContacts();
  }

  public GroupInfo getGroup(byte[] groupId) { return accountData.groupStore.getGroup(groupId); }

  public List<IdentityKeysTable.IdentityKeyRow> getIdentities() throws SQLException, InvalidKeyException { return accountData.axolotlStore.getIdentities(); }

  public List<IdentityKeysTable.IdentityKeyRow> getIdentities(SignalServiceAddress address) throws SQLException, InvalidKeyException, InvalidAddressException {
    return accountData.axolotlStore.getIdentities(address);
  }

  public boolean trustIdentity(SignalServiceAddress address, byte[] fingerprint, TrustLevel level) throws IOException, SQLException, InvalidKeyException, InvalidAddressException {
    List<IdentityKeysTable.IdentityKeyRow> ids = accountData.axolotlStore.getIdentities(address);
    if (ids == null) {
      return false;
    }
    for (IdentityKeysTable.IdentityKeyRow id : ids) {
      if (!Arrays.equals(id.getKey().serialize(), fingerprint)) {
        continue;
      }

      accountData.axolotlStore.saveIdentity(address, id.getKey(), level);
      try {
        sendVerifiedMessage(address, id.getKey(), level);
      } catch (IOException | org.whispersystems.signalservice.api.crypto.UntrustedIdentityException e) {
        logger.catching(e);
      }
      return true;
    }
    return false;
  }

  public boolean trustIdentitySafetyNumber(SignalServiceAddress address, String safetyNumber, TrustLevel level) throws SQLException, InvalidKeyException, InvalidAddressException {
    List<IdentityKeysTable.IdentityKeyRow> ids = accountData.axolotlStore.getIdentities(address);
    if (ids == null) {
      return false;
    }
    for (IdentityKeysTable.IdentityKeyRow id : ids) {
      if (!safetyNumber.equals(SafetyNumberHelper.computeSafetyNumber(accountData.address.getSignalServiceAddress(), getIdentity(), address, id.getKey()))) {
        continue;
      }
      accountData.axolotlStore.saveIdentity(address, id.getKey(), level);
      try {
        sendVerifiedMessage(address, id.getKey(), level);
      } catch (IOException | org.whispersystems.signalservice.api.crypto.UntrustedIdentityException e) {
        logger.catching(e);
      }
      return true;
    }
    return false;
  }

  public boolean trustIdentitySafetyNumber(SignalServiceAddress address, byte[] scannedFingerprintData, TrustLevel level)
      throws IOException, FingerprintVersionMismatchException, FingerprintParsingException, SQLException, InvalidKeyException, InvalidAddressException {
    List<IdentityKeysTable.IdentityKeyRow> ids = accountData.axolotlStore.getIdentities(address);
    if (ids == null) {
      return false;
    }
    for (IdentityKeysTable.IdentityKeyRow id : ids) {
      Fingerprint fingerprint = SafetyNumberHelper.computeFingerprint(getOwnAddress(), getIdentity(), address, id.getKey());
      assert fingerprint != null;
      if (!fingerprint.getScannableFingerprint().compareTo(scannedFingerprintData)) {
        continue;
      }

      accountData.axolotlStore.saveIdentity(address, id.getKey(), level);
      try {
        sendVerifiedMessage(address, id.getKey(), level);
      } catch (IOException | org.whispersystems.signalservice.api.crypto.UntrustedIdentityException e) {
        logger.catching(e);
      }
      return true;
    }
    return false;
  }

  public Optional<ContactTokenDetails> getUser(String e164number) throws IOException { return getAccountManager().getContact(e164number); }

  public List<Optional<UnidentifiedAccessPair>> getAccessFor(Collection<SignalServiceAddress> recipients) {
    List<Optional<UnidentifiedAccessPair>> result = new ArrayList<>(recipients.size());
    for (SignalServiceAddress ignored : recipients) {
      result.add(Optional.absent());
    }
    return result;
  }

  public Optional<UnidentifiedAccessPair> getAccessFor(SignalServiceAddress recipient) {
    // TODO implement
    return Optional.absent();
  }

  public void setProfile(String name, File avatar) throws IOException, InvalidInputException {
    try (final StreamDetails streamDetails = avatar == null ? null : AttachmentUtil.createStreamDetailsFromFile(avatar)) {
      getAccountManager().setVersionedProfile(accountData.address.getUUID(), accountData.getProfileKey(), name, "", "", Optional.absent(), streamDetails);
    }
  }

  public void setProfile(String name, File avatar, String about, String emoji) throws IOException, InvalidInputException {
    if (name == null) {
      name = "";
    }
    if (about == null) {
      about = "";
    }
    if (emoji == null) {
      emoji = "";
    }
    try (final StreamDetails streamDetails = avatar == null ? null : AttachmentUtil.createStreamDetailsFromFile(avatar)) {
      getAccountManager().setVersionedProfile(accountData.address.getUUID(), accountData.getProfileKey(), name, about, emoji, Optional.absent(), streamDetails);
    }
  }

  public SignalServiceProfile getSignalServiceProfile(SignalServiceAddress address, ProfileKey profileKey) throws InterruptedException, ExecutionException, TimeoutException {
    final SignalServiceMessageReceiver messageReceiver = getMessageReceiver();
    ListenableFuture<ProfileAndCredential> profile = messageReceiver.retrieveProfile(address, Optional.of(profileKey), Optional.absent(), SignalServiceProfile.RequestType.PROFILE);
    return profile.get(10, TimeUnit.SECONDS).getProfile();
  }

  public SignalServiceMessageSender getMessageSender() {
    return new SignalServiceMessageSender(serviceConfiguration, accountData.getCredentialsProvider(), accountData.axolotlStore, new SessionLock(accountData.getUUID()),
                                          BuildConfig.SIGNAL_AGENT, true, Optional.fromNullable(messagePipe), Optional.fromNullable(unidentifiedMessagePipe), Optional.absent(),
                                          getClientZkOperations().getProfileOperations(), null, 0, true);
  }

  public SignalServiceMessageReceiver getMessageReceiver() {
    //SignalServiceConfiguration urls, UUID uuid, String e164, String password, int deviceId, String signalingKey, String signalAgent, ConnectivityListener listener, SleepTimer timer, ClientZkProfileOperations clientZkProfileOperations, boolean automaticNetworkRetry
    return new SignalServiceMessageReceiver(serviceConfiguration, accountData.address.getUUID(), accountData.username, accountData.password, accountData.deviceId, USER_AGENT, null,
                                            sleepTimer, getClientZkOperations().getProfileOperations(), true);
  }

  public static ClientZkOperations getClientZkOperations() { return ClientZkOperations.create(generateSignalServiceConfiguration()); }

  public RecipientsTable getResolver() { return accountData.getResolver(); }

  public void refreshAccount() throws IOException, SQLException {
    String deviceName = AccountDataTable.getString(getUUID(), AccountDataTable.Key.DEVICE_NAME);
    if (deviceName == null) {
      deviceName = "signald";
    }
    deviceName = DeviceNameUtil.encryptDeviceName(deviceName, accountData.axolotlStore.getIdentityKeyPair().getPrivateKey());
    getAccountManager().setAccountAttributes(deviceName, accountData.signalingKey, accountData.axolotlStore.getLocalRegistrationId(), true, null, null, null, true,
                                             SERVICE_CAPABILITIES, true);
    if (accountData.lastAccountRefresh < ACCOUNT_REFRESH_VERSION) {
      accountData.lastAccountRefresh = ACCOUNT_REFRESH_VERSION;
      accountData.save();
    }
  }

  public GroupsV2Manager getGroupsV2Manager() { return groupsV2Manager; }

  private void refreshAccountIfNeeded() throws IOException, SQLException {
    if (accountData.lastAccountRefresh < ACCOUNT_REFRESH_VERSION) {
      refreshAccount();
    }
  }

  public AccountData getAccountData() { return accountData; }

  public ProfileAndCredentialEntry getRecipientProfileKeyCredential(SignalServiceAddress address) throws InterruptedException, ExecutionException, TimeoutException, IOException {
    ProfileAndCredentialEntry profileEntry = accountData.profileCredentialStore.get(address);
    if (profileEntry == null) {
      return null;
    }
    RefreshProfileJob action = new RefreshProfileJob(this, profileEntry);
    if (action.needsRefresh()) {
      action.run();
      return accountData.profileCredentialStore.get(address);
    } else {
      return profileEntry;
    }
  }

  public SignalProfile decryptProfile(final SignalServiceAddress address, final ProfileKey profileKey, final SignalServiceProfile encryptedProfile) throws IOException {
    File localAvatarPath = null;
    if (address.getUuid().isPresent()) {
      localAvatarPath = getProfileAvatarFile(address);
      if (encryptedProfile.getAvatar() != null) {
        createPrivateDirectories(avatarsPath);
        try (OutputStream outputStream = new FileOutputStream(localAvatarPath)) {
          retrieveProfileAvatar(encryptedProfile.getAvatar(), profileKey, outputStream);
        } catch (IOException e) {
          logger.info("Failed to retrieve profile avatar, ignoring: " + e.getMessage());
        }
      }
    }

    ProfileCipher profileCipher = new ProfileCipher(profileKey);

    String name;
    try {
      name = encryptedProfile.getName() == null ? null : profileCipher.decryptString(Base64.decode(encryptedProfile.getName()));
    } catch (InvalidCiphertextException e) {
      name = null;
      logger.debug("error decrypting profile name.", e);
    }

    String about;
    try {
      about = encryptedProfile.getAbout() == null ? null : profileCipher.decryptString(Base64.decode(encryptedProfile.getAbout()));
    } catch (InvalidCiphertextException e) {
      about = null;
      logger.debug("error decrypting profile about text.", e);
    }

    String aboutEmoji;
    try {
      aboutEmoji = encryptedProfile.getAboutEmoji() == null ? null : profileCipher.decryptString(Base64.decode(encryptedProfile.getAboutEmoji()));
    } catch (InvalidCiphertextException e) {
      aboutEmoji = null;
      logger.debug("error decrypting profile emoji.", e);
    }

    String unidentifiedAccess;
    try {
      unidentifiedAccess = encryptedProfile.getUnidentifiedAccess() == null || !profileCipher.verifyUnidentifiedAccess(Base64.decode(encryptedProfile.getUnidentifiedAccess()))
                               ? null
                               : encryptedProfile.getUnidentifiedAccess();
    } catch (IOException e) {
      unidentifiedAccess = null;
    }
    return new SignalProfile(encryptedProfile, name, about, aboutEmoji, localAvatarPath, unidentifiedAccess);
  }

  private void retrieveProfileAvatar(String avatarsPath, ProfileKey profileKey, OutputStream outputStream) throws IOException {
    File tmpFile = Util.createTempFile();
    try (InputStream input = getMessageReceiver().retrieveProfileAvatar(avatarsPath, tmpFile, profileKey, AVATAR_DOWNLOAD_FAILSAFE_MAX_SIZE)) {
      Util.copyStream(input, outputStream, (int)AVATAR_DOWNLOAD_FAILSAFE_MAX_SIZE);
    } finally {
      try {
        Files.delete(tmpFile.toPath());
      } catch (IOException e) {
        logger.warn("Failed to delete received profile avatar temp file “{}”, ignoring: {}", tmpFile, e.getMessage());
      }
    }
  }

  public void deleteAccount(boolean remote) throws IOException, SQLException {
    if (remote) {
      getAccountManager().deleteAccount();
    }
    accountData.delete();
    managers.remove(accountData.username);
    logger.info("deleted all local account data");
  }
}

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

package io.finn.signald.storage;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import io.finn.signald.db.PreKeysTable;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.whispersystems.libsignal.InvalidKeyIdException;
import org.whispersystems.libsignal.state.PreKeyRecord;
import org.whispersystems.util.Base64;

import java.io.IOException;
import java.util.*;

@JsonDeserialize(using = PreKeyStore.JsonPreKeyStoreDeserializer.class)
@JsonSerialize(using = PreKeyStore.JsonPreKeyStoreSerializer.class)
public class PreKeyStore implements org.whispersystems.libsignal.state.PreKeyStore {
  private static final Logger logger = LogManager.getLogger();
  private final Map<Integer, byte[]> store = new HashMap<>();

  @Override
  public PreKeyRecord loadPreKey(int preKeyId) throws InvalidKeyIdException {
    if (!store.containsKey(preKeyId)) {
      throw new InvalidKeyIdException("No such prekeyrecord!");
    }

    try {
      return new PreKeyRecord(store.get(preKeyId));
    } catch (IOException e) {
      throw new AssertionError(e);
    }
  }

  public void migrateToDB(UUID u) {
    PreKeysTable table = new PreKeysTable(u);
    Iterator<Map.Entry<Integer, byte[]>> iterator = store.entrySet().iterator();
    logger.info("migrating " + store.size() + " prekeys to database");
    while (iterator.hasNext()) {
      Map.Entry<Integer, byte[]> entry = iterator.next();
      try {
        table.storePreKey(entry.getKey(), new PreKeyRecord(entry.getValue()));
        iterator.remove();
      } catch (IOException e) {
        logger.warn("failed to migrate prekey record", e);
      }
    }
  }

  @Override
  public void storePreKey(int preKeyId, PreKeyRecord record) {
    store.put(preKeyId, record.serialize());
  }

  @Override
  public boolean containsPreKey(int preKeyId) {
    return store.containsKey(preKeyId);
  }

  @Override
  public void removePreKey(int preKeyId) {
    store.remove(preKeyId);
  }

  public static class JsonPreKeyStoreDeserializer extends JsonDeserializer<PreKeyStore> {
    @Override
    public PreKeyStore deserialize(JsonParser jsonParser, DeserializationContext deserializationContext) throws IOException {
      JsonNode node = jsonParser.getCodec().readTree(jsonParser);
      Map<Integer, byte[]> preKeyMap = new HashMap<>();
      if (node.isArray()) {
        for (JsonNode preKey : node) {
          Integer preKeyId = preKey.get("id").asInt();
          try {
            preKeyMap.put(preKeyId, Base64.decode(preKey.get("record").asText()));
          } catch (IOException e) {
            logger.error("Error while decoding prekey for: " + preKeyId, e);
          }
        }
      }
      PreKeyStore keyStore = new PreKeyStore();
      keyStore.store.putAll(preKeyMap);
      return keyStore;
    }
  }

  public static class JsonPreKeyStoreSerializer extends JsonSerializer<PreKeyStore> {
    @Override
    public void serialize(PreKeyStore jsonPreKeyStore, JsonGenerator json, SerializerProvider serializerProvider) throws IOException {
      json.writeStartArray();
      for (Map.Entry<Integer, byte[]> preKey : jsonPreKeyStore.store.entrySet()) {
        json.writeStartObject();
        json.writeNumberField("id", preKey.getKey());
        json.writeStringField("record", Base64.encodeBytes(preKey.getValue()));
        json.writeEndObject();
      }
      json.writeEndArray();
    }
  }
}

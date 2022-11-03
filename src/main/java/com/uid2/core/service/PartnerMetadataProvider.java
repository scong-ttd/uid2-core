package com.uid2.core.service;

import com.uid2.core.model.SecretStore;
import com.uid2.shared.cloud.ICloudStorage;
import io.vertx.core.json.Json;
import io.vertx.core.json.JsonObject;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;

public class PartnerMetadataProvider implements IPartnerMetadataProvider {

    public static final String PartnersMetadataPathName = "partners_metadata_path";

    private final ICloudStorage metadataStreamProvider;
    private final ICloudStorage downloadUrlGenerator;

    @Override
    public String getMetadata() throws Exception {
        String original = readToEndAsString(metadataStreamProvider.download(SecretStore.Global.get(PartnersMetadataPathName)));
        JsonObject main = (JsonObject) Json.decodeValue(original);
        JsonObject obj = main.getJsonObject("partners");
        String location = obj.getString("location");
        obj.put("location", downloadUrlGenerator.preSignUrl(location).toString());
        return main.encode();
    }

    public PartnerMetadataProvider(ICloudStorage cloudStorage) {
        this.metadataStreamProvider = this.downloadUrlGenerator = cloudStorage;
    }

    public PartnerMetadataProvider(ICloudStorage fileStreamProvider, ICloudStorage downloadUrlGenerator) {
        this.metadataStreamProvider = fileStreamProvider;
        this.downloadUrlGenerator = downloadUrlGenerator;
    }

    private static String readToEndAsString(InputStream stream) throws IOException {
        final InputStreamReader reader = new InputStreamReader(stream);
        final char[] buff = new char[1024];
        final StringBuilder sb = new StringBuilder();
        for (int count; (count = reader.read(buff, 0, buff.length)) > 0;) {
            sb.append(buff, 0, count);
        }
        return sb.toString();
    }
}

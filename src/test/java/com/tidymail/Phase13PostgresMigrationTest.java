package com.tidymail;

import static org.assertj.core.api.Assertions.assertThat;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Keeps published migration SQL immutable; current DB upgrades are tested separately. */
class Phase13PostgresMigrationTest {
    private static final Map<Integer, String> PRODUCTION_HASHES = hashes(
        "ab67ba33bbd733ec00dfe1a9f816226f6e0220994b23660bafa462a80e78b418",
        "445c0a0854bf679b85416985fea97b3a9184e0df817c9bc3c325955350c9ddd6",
        "2caa13f0770ee3111b03df19256b36d3825a8aa1eafcd60a03970f54d1c571a1",
        "4d851ef2470654129f6c8c1e11a5d27d3e403fdab177a5ad5eea69e13823cd5c",
        "8e3301300edee870d4c31eafdada4da8401510acacddcd129f49f33b30d259fb",
        "9d59cdc1a0f22c0df01407df7754d9fec92cbcc2eb59be131516fbdcc3c1d116",
        "8b04c928007a9b90abbc8dbe9502f5227d295c13c21f47bc4f02ee901a14fb5c",
        "190c0d9cf10ca687bafc0604325aaa47380ae91593b1dbc2d669e9ba27256461",
        "c092433908b88c3d6f7df912e09abd53348874b26af0c9d5163b35eb98ece379",
        "97323832aaa62a5bcabee502d99f97eb532c1287537eb942d0f739eb10776322");

    @Test
    void publishedMigrationSqlHasNotChanged() throws Exception {
        assertFrozenMigrations("src/main/resources/db/migration", PRODUCTION_HASHES);
    }

    private static String sha256(Path path) throws Exception {
        return HexFormat.of().formatHex(
            MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)));
    }

    private static Map<Integer, String> hashes(String... values) {
        LinkedHashMap<Integer, String> hashes = new LinkedHashMap<>();
        for (int index = 0; index < values.length; index++) {
            hashes.put(index + 1, values[index]);
        }
        return Map.copyOf(hashes);
    }

    private static void assertFrozenMigrations(
            String directory, Map<Integer, String> expected) throws Exception {
        for (Map.Entry<Integer, String> entry : expected.entrySet()) {
            List<Path> matches;
            try (var files = Files.list(Path.of(directory))) {
                String prefix = "V" + entry.getKey() + "__";
                matches = files.filter(path -> path.getFileName().toString().startsWith(prefix))
                    .toList();
            }
            assertThat(matches).as("migration V%s in %s", entry.getKey(), directory)
                .singleElement()
                .satisfies(path -> assertThat(sha256(path)).isEqualTo(entry.getValue()));
        }
    }
}

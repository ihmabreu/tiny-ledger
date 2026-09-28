package com.teya.tinyledger.integration;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.config.DecoderConfig;
import io.restassured.config.RestAssuredConfig;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;
import java.util.zip.GZIPInputStream;
import java.util.zip.InflaterInputStream;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.nullValue;

/**
 * Integration tests for negotiated HTTP response compression.
 *
 * <p>Compression is a transport concern, and it makes a deliberately narrow promise: the bytes on
 * the wire get smaller and <em>nothing else changes</em>. These tests are written around that
 * promise rather than around the presence of a header, because a {@code Content-Encoding: gzip}
 * header on a body that was never actually compressed &mdash; or that decodes to something other
 * than the uncompressed representation &mdash; would satisfy a header-only assertion while
 * breaking every client.</p>
 *
 * <p>Each test therefore asserts one of three things:</p>
 * <ol>
 *   <li><b>It is negotiated.</b> The server compresses when the client asks and never when it
 *       does not. An encoding imposed on a client that never advertised it is a broken response,
 *       not an optimisation.</li>
 *   <li><b>It is real.</b> The body carries the format's magic number, decodes cleanly, and is
 *       genuinely smaller than the plain form.</li>
 *   <li><b>It is transparent.</b> The decoded body is identical to what the same request returns
 *       uncompressed, so the published contract is untouched.</li>
 * </ol>
 *
 * <p>Every request here is built with {@link DecoderConfig#noContentDecoders()}. RestAssured
 * otherwise advertises {@code Accept-Encoding: gzip,deflate} of its own accord and transparently
 * decodes the reply, which would make a compressed and an uncompressed response indistinguishable
 * and leave these tests asserting nothing at all. Disabling the decoders both suppresses that
 * automatic header and hands over the raw bytes, which is the only way to see what actually
 * crossed the wire.</p>
 *
 * <p>That default is worth noting for the opposite reason too: it means every <em>other</em> test
 * in this tier already exercises the compressed path end to end without mentioning it, and their
 * continued passing is itself evidence that compression is transparent.</p>
 */
@QuarkusTest
@Tag("integration")
@DisplayName("HTTP compression")
class HttpCompressionTest extends AbstractContractTest {

    private static final String GZIP = "gzip";
    private static final String DEFLATE = "deflate";

    /**
     * Starts a request that neither advertises nor decodes any content encoding.
     *
     * @return a request specification that leaves compression entirely to the caller
     */
    private static RequestSpecification wireClient() {
        return given()
                .config(RestAssuredConfig.config()
                        .decoderConfig(DecoderConfig.decoderConfig().noContentDecoders()))
                .accept(ContentType.JSON);
    }

    /**
     * Opens an account and records enough movements to make a response worth compressing.
     *
     * <p>Repetitive JSON is precisely what compression is for &mdash; the field names repeat on
     * every element &mdash; so a page of movements is the realistic case worth measuring.</p>
     *
     * @param ownerName the account holder
     * @param movements how many deposits to record
     * @return the new account's identifier
     */
    private static String accountWithHistory(String ownerName, int movements) {
        String accountId = openAccount(ownerName, "EUR", "0.00");
        for (int index = 0; index < movements; index++) {
            record(accountId, "DEPOSIT", "10.00", "EUR", "Movement number " + index)
                    .then().statusCode(201);
        }
        return accountId;
    }

    private static boolean hasGzipMagicNumber(byte[] bytes) {
        return bytes != null
                && bytes.length >= 2
                && bytes[0] == (byte) 0x1f
                && bytes[1] == (byte) 0x8b;
    }

    private static String decodeGzip(byte[] compressed) {
        try (var in = new GZIPInputStream(new ByteArrayInputStream(compressed))) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Response was announced as gzip but did not decode", e);
        }
    }

    private static String decodeDeflate(byte[] compressed) {
        try (var in = new InflaterInputStream(new ByteArrayInputStream(compressed))) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Response was announced as deflate but did not decode", e);
        }
    }

    @Nested
    @DisplayName("when the client advertises gzip")
    class WhenGzipIsAdvertised {

        /**
         * Fetches a path as gzip, proving the reply really is gzip, and returns the decoded body.
         *
         * @param path      the path to fetch
         * @param arguments any path parameters
         * @return the decompressed response body
         */
        private String fetchGzipped(String path, Object... arguments) {
            Response response = wireClient()
                    .header("Accept-Encoding", GZIP)
                    .when().get(path, arguments)
                    .then()
                    .statusCode(200)
                    .header("Content-Encoding", equalTo(GZIP))
                    .extract().response();

            byte[] wireBytes = response.asByteArray();
            assertThat(hasGzipMagicNumber(wireBytes))
                    .as("a body announced as gzip should begin with the gzip magic number")
                    .isTrue();

            return decodeGzip(wireBytes);
        }

        @Test
        @DisplayName("compresses the account listing")
        void listAccounts_advertisingGzip_returnsDecodableGzipBody() {
            openAccount("Gzip Listing Owner", "EUR", "100.00");

            assertThat(fetchGzipped("/api/v1/accounts")).contains("Gzip Listing Owner");
        }

        @Test
        @DisplayName("compresses a single account")
        void getAccount_advertisingGzip_returnsDecodableGzipBody() {
            String accountId = openAccount("Gzip Account Owner", "EUR", "250.00");

            assertThat(fetchGzipped("/api/v1/accounts/{accountId}", accountId))
                    .contains(accountId)
                    .contains("250.00");
        }

        @Test
        @DisplayName("compresses the balance")
        void getBalance_advertisingGzip_returnsDecodableGzipBody() {
            String accountId = openAccount("Gzip Balance Owner", "EUR", "250.00");

            assertThat(fetchGzipped("/api/v1/accounts/{accountId}/balance", accountId))
                    .contains(accountId)
                    .contains("250.00");
        }

        @Test
        @DisplayName("compresses the transaction history")
        void getTransactions_advertisingGzip_returnsDecodableGzipBody() {
            String accountId = accountWithHistory("Gzip History Owner", 3);

            assertThat(fetchGzipped("/api/v1/accounts/{accountId}/transactions", accountId))
                    .contains("Movement number 0")
                    .contains("Movement number 2");
        }

        @Test
        @DisplayName("compresses a created account, not only reads")
        void openAccount_advertisingGzip_returnsDecodableGzipBody() {
            Response response = wireClient()
                    .header("Accept-Encoding", GZIP)
                    .contentType(ContentType.JSON)
                    .body("""
                            {"ownerName":"Gzip Created Owner","currency":"EUR","overdraftLimit":"50.00"}
                            """)
                    .when().post("/api/v1/accounts")
                    .then()
                    .statusCode(201)
                    .header("Content-Encoding", equalTo(GZIP))
                    .extract().response();

            assertThat(decodeGzip(response.asByteArray())).contains("Gzip Created Owner");
        }

        @Test
        @DisplayName("compresses a recorded movement, not only reads")
        void recordTransaction_advertisingGzip_returnsDecodableGzipBody() {
            String accountId = openAccount("Gzip Movement Owner", "EUR", "0.00");

            Response response = wireClient()
                    .header("Accept-Encoding", GZIP)
                    .header("Idempotency-Key", UUID.randomUUID().toString())
                    .contentType(ContentType.JSON)
                    .body("""
                            {"type":"DEPOSIT","amount":"200.00","currency":"EUR","reference":"Bonus","occurredAt":"%s"}
                            """.formatted(Instant.now()))
                    .when().post("/api/v1/accounts/{accountId}/transactions", accountId)
                    .then()
                    .statusCode(201)
                    .header("Content-Encoding", equalTo(GZIP))
                    .extract().response();

            assertThat(decodeGzip(response.asByteArray()))
                    .contains("200.00")
                    .contains("Bonus");
        }
    }

    @Nested
    @DisplayName("when the client does not advertise gzip")
    class WhenGzipIsNotAdvertised {

        @Test
        @DisplayName("sends a plain body when no Accept-Encoding is offered at all")
        void getAccount_withoutAcceptEncoding_returnsPlainBody() {
            String accountId = openAccount("Plain Owner", "EUR", "0.00");

            wireClient()
                    .when().get("/api/v1/accounts/{accountId}", accountId)
                    .then()
                    .statusCode(200)
                    .header("Content-Encoding", nullValue())
                    .body("ownerName", equalTo("Plain Owner"));
        }

        @Test
        @DisplayName("sends a plain body when only identity is acceptable")
        void getAccount_advertisingIdentityOnly_returnsPlainBody() {
            String accountId = openAccount("Identity Owner", "EUR", "0.00");

            wireClient()
                    .header("Accept-Encoding", "identity")
                    .when().get("/api/v1/accounts/{accountId}", accountId)
                    .then()
                    .statusCode(200)
                    .header("Content-Encoding", nullValue())
                    .body("ownerName", equalTo("Identity Owner"));
        }

        @Test
        @DisplayName("sends a plain body when only an encoding it cannot produce is offered")
        void getAccount_advertisingOnlyAnUnsupportedEncoding_returnsPlainBody() {
            String accountId = openAccount("Unsupported Encoding Owner", "EUR", "0.00");

            wireClient()
                    .header("Accept-Encoding", "br")
                    .when().get("/api/v1/accounts/{accountId}", accountId)
                    .then()
                    .statusCode(200)
                    .header("Content-Encoding", nullValue())
                    .body("ownerName", equalTo("Unsupported Encoding Owner"));
        }
    }

    @Nested
    @DisplayName("as a guarantee")
    class AsAGuarantee {

        @Test
        @DisplayName("shrinks the bytes that actually cross the wire")
        void getTransactions_comparedAcrossEncodings_isSmallerOnTheWireWhenCompressed() {
            String accountId = accountWithHistory("Payload Size Owner", 25);
            String path = "/api/v1/accounts/{accountId}/transactions";

            byte[] plain = wireClient()
                    .when().get(path, accountId)
                    .then().statusCode(200)
                    .extract().asByteArray();

            byte[] compressed = wireClient()
                    .header("Accept-Encoding", GZIP)
                    .when().get(path, accountId)
                    .then().statusCode(200)
                    .header("Content-Encoding", equalTo(GZIP))
                    .extract().asByteArray();

            assertThat(compressed.length)
                    .as("a page of repetitive JSON should compress substantially")
                    .isLessThan(plain.length);
        }

        @Test
        @DisplayName("changes the encoding of a response without changing the response")
        void getTransactions_comparedAcrossEncodings_decodesToTheIdenticalBody() {
            String accountId = accountWithHistory("Transparency Owner", 5);
            String path = "/api/v1/accounts/{accountId}/transactions";

            String plain = wireClient()
                    .when().get(path, accountId)
                    .then().statusCode(200)
                    .extract().asString();

            byte[] compressed = wireClient()
                    .header("Accept-Encoding", GZIP)
                    .when().get(path, accountId)
                    .then().statusCode(200)
                    .header("Content-Encoding", equalTo(GZIP))
                    .extract().asByteArray();

            assertThat(decodeGzip(compressed))
                    .as("compression must be a transport detail, never a change to the payload")
                    .isEqualTo(plain);
        }

        @Test
        @DisplayName("honours deflate as well, for clients that prefer it")
        void getAccount_advertisingDeflate_returnsDecodableDeflateBody() {
            String accountId = openAccount("Deflate Owner", "EUR", "0.00");

            Response response = wireClient()
                    .header("Accept-Encoding", DEFLATE)
                    .when().get("/api/v1/accounts/{accountId}", accountId)
                    .then()
                    .statusCode(200)
                    .header("Content-Encoding", equalTo(DEFLATE))
                    .extract().response();

            assertThat(decodeDeflate(response.asByteArray())).contains("Deflate Owner");
        }
    }
}

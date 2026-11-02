import com.fasterxml.jackson.databind.*;
import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport;
import com.google.api.client.http.*;
import com.google.api.services.gmail.Gmail;
import com.google.api.client.json.jackson2.JacksonFactory;
import com.tidymail.config.OAuthProviderAvailability;
import com.tidymail.security.crypto.*;
import org.springframework.boot.*;
import org.springframework.boot.autoconfigure.*;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.client.endpoint.*;
import org.springframework.security.oauth2.client.registration.*;
import org.springframework.security.oauth2.core.*;
import org.springframework.security.oauth2.core.endpoint.OAuth2AccessTokenResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.util.*;

/** Opt-in, bounded GET-only Gmail capability probe. No component scan, jobs, migrations,
 * mailbox changes, model calls, body files or credential output. Credentials are loaded
 * through existing app configuration/crypto and refreshed only in memory if necessary. */
public class GmailEvidenceProbe {
 @Configuration(proxyBeanMethods=false)
 @EnableAutoConfiguration(excludeName={
  "org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration",
  "org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration",
  "org.springframework.boot.autoconfigure.data.jpa.JpaRepositoriesAutoConfiguration",
  "org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration",
  "org.springframework.boot.autoconfigure.security.oauth2.client.servlet.OAuth2ClientAutoConfiguration"})
 @Import({CryptoProperties.class,CryptoUtil.class,OAuthProviderAvailability.class})
 static class Config {}
 static final ObjectMapper JSON=new ObjectMapper();
 static final int RESPONSE_LIMIT=1024*1024, TOTAL_LIMIT=8*1024*1024, REQUEST_LIMIT=64;
 static int calls=0,bytesRead=0;
 static Gmail gmail;
 static final List<Map<String,Object>> requests=new ArrayList<>();
 static final List<String> calendars=new ArrayList<>();
 static final Map<String,Object> report=new LinkedHashMap<>();
 record Sample(String id,String cohort){}
 record Response(JsonNode node,int status,int bytes,long millis,boolean capped){}
 public static void main(String[] args) throws Exception {
  if(args.length!=3||!Set.of("--read-only-sample","--read-only-cost-check").contains(args[0]))throw new IllegalArgumentException("Explicit --read-only-sample, report path and calendar-parser path required");
  boolean costCheck=args[0].equals("--read-only-cost-check");
  report.put("probe_mode",args[0]);report.put("observed_at_utc",Instant.now().toString());report.put("mailbox_mutations",0);report.put("model_calls",0);report.put("body_content_persisted",false);
  report.put("limits",Map.of("max_requests",REQUEST_LIMIT,"max_response_bytes",RESPONSE_LIMIT,"max_total_response_bytes",TOTAL_LIMIT));
  try(var ctx=new SpringApplicationBuilder(Config.class).web(WebApplicationType.NONE)
    .properties(Map.of("spring.main.banner-mode","off","logging.level.root","OFF","logging.level.com.tidymail","OFF",
     "spring.sql.init.mode","never","spring.datasource.hikari.read-only","true","spring.datasource.hikari.maximum-pool-size","1","spring.datasource.hikari.minimum-idle","0"))
    .run("--logging.level.root=OFF","--logging.level.com.tidymail=OFF","--spring.main.banner-mode=off")) {
   var jdbc=ctx.getBean(JdbcTemplate.class);
   var accounts=jdbc.queryForList("SELECT id,scopes,encrypted_access_token,encrypted_refresh_token,token_issued_at,access_expires_at FROM user_accounts WHERE provider='google' AND oauth_status='ACTIVE' ORDER BY id");
   if(accounts.size()!=1)throw new IllegalStateException("Expected exactly one active Gmail account");
   var account=accounts.getFirst();long accountId=((Number)account.get("id")).longValue();
   var scopeArray=(java.sql.Array)account.get("scopes");var scopes=new HashSet<>(Arrays.asList((String[])scopeArray.getArray()));
   report.put("stored_scopes",scopes);report.put("body_read_scope_granted",scopes.contains("https://www.googleapis.com/auth/gmail.modify")||scopes.contains("https://www.googleapis.com/auth/gmail.readonly"));
   if(!Boolean.TRUE.equals(report.get("body_read_scope_granted")))throw new IllegalStateException("Body-read grant not present; do not expand permissions automatically");
   CryptoUtil crypto=ctx.getBean(CryptoUtil.class);String token=new String(crypto.decrypt((byte[])account.get("encrypted_access_token")),StandardCharsets.UTF_8);
   Instant expiry=((java.sql.Timestamp)account.get("access_expires_at")).toInstant();
   if(expiry.isBefore(Instant.now().plusSeconds(90))) {
    var props=ctx.getBean(OAuthProviderAvailability.class);
    var reg=ClientRegistration.withRegistrationId("google").clientId(props.getGoogleClientId()).clientSecret(props.getGoogleClientSecret())
     .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE).redirectUri("http://localhost/unused")
     .authorizationUri("https://accounts.google.com/o/oauth2/v2/auth").tokenUri("https://oauth2.googleapis.com/token").scope(scopes).build();
    var access=new OAuth2AccessToken(OAuth2AccessToken.TokenType.BEARER,token,((java.sql.Timestamp)account.get("token_issued_at")).toInstant(),expiry,scopes);
    var refresh=new OAuth2RefreshToken(new String(crypto.decrypt((byte[])account.get("encrypted_refresh_token")),StandardCharsets.UTF_8),access.getIssuedAt());
    OAuth2AccessTokenResponse response=new DefaultRefreshTokenTokenResponseClient().getTokenResponse(new OAuth2RefreshTokenGrantRequest(reg,access,refresh));
    token=response.getAccessToken().getTokenValue();report.put("credential_refresh","performed in memory; database unchanged");
   }else report.put("credential_refresh","not needed");
   final String bearer=token;
   gmail=new Gmail.Builder(GoogleNetHttpTransport.newTrustedTransport(),JacksonFactory.getDefaultInstance(),req->{req.setConnectTimeout(10000);req.setReadTimeout(20000);req.setNumberOfRetries(0);req.getHeaders().setAuthorization("Bearer "+bearer);}).setApplicationName("TidyMail-read-only-evidence-probe").build();
   System.out.println("Stored grant verified; beginning bounded Gmail reads");
   report.put("index",jdbc.queryForMap("SELECT count(*) messages,count(size_estimate) with_size,count(*) FILTER(WHERE size_estimate>5000000) over_5mb_all_folders,count(*) FILTER(WHERE attachment_state='UNKNOWN') unknown_attachment_state FROM message_metadata WHERE account_id=?",accountId));
   var samples=new LinkedHashMap<String,Sample>();
   for(String domain:List.of("amazon.com","quora.com","nextdoor.com","costco.com")) {
    var ids=jdbc.queryForList("SELECT provider_message_id FROM message_metadata WHERE account_id=? AND (from_domain=? OR from_domain LIKE ?) AND received_at>=? AND NOT(labels && ARRAY['SPAM','TRASH','DRAFT']) ORDER BY received_at DESC LIMIT 1",String.class,accountId,domain,"%."+domain,java.sql.Timestamp.from(Instant.now().minus(Duration.ofDays(730))));
    ids.forEach(id->samples.putIfAbsent(id,new Sample(id,"brand:"+domain)));
   }
   if(costCheck)addSearch(samples,"large_without_attachment_search","larger:5M -has:attachment -in:spam -in:trash",1);
   else {addSearch(samples,"calendar","after:2024/11/01 -in:spam -in:trash filename:ics",4);
   addSearch(samples,"large","after:2024/11/01 -in:spam -in:trash larger:5M",2);}
   List<Map<String,Object>> observations=new ArrayList<>();int serial=0,bodyBrands=0,bodyCalendars=0;
   for(Sample sample:samples.values()) {
    if(++serial>12)break;String code="S"+String.format("%02d",serial);
    var row=new LinkedHashMap<String,Object>();row.put("sample",code);row.put("cohort",sample.cohort());
    String base="id,threadId,internalDate,historyId,labelIds,sizeEstimate,payload(headers)";
    Response meta=fetchMessage(sample.id(),"METADATA",base,code+":metadata_baseline");
    row.put("metadata_baseline",summarize(meta));if(meta.status()!=200){observations.add(row);continue;}
    if(costCheck) {
     Response plus=fetchMessage(sample.id(),"METADATA",base+",snippet",code+":matched_metadata_plus_snippet");
     row.put("matched_metadata_plus_snippet",summarize(plus));row.put("extra_json_bytes",plus.bytes()-meta.bytes());
     row.put("same_fields_except_snippet",true);row.put("over_5mb",meta.node().path("sizeEstimate").asLong()>5000000);
     if(sample.cohort().equals("large_without_attachment_search")) {
      Response structure=fetchMessage(sample.id(),"FULL","id,sizeEstimate,payload("+partFields(6,false)+")",code+":large_structure_no_data");
      row.put("full_structure_no_data",summarize(structure));var pp=parts(structure.node().path("payload"));
      row.put("mime_types",pp.stream().map(v->v.path("mimeType").asText()).distinct().toList());
      row.put("named_parts",pp.stream().filter(v->!v.path("filename").asText().isBlank()).count());
      row.put("image_parts",pp.stream().filter(v->v.path("mimeType").asText().startsWith("image/")).count());
     }
     observations.add(row);System.out.println(code+" "+sample.cohort()+" matched-size check verified");continue;
    }

    Response enriched=fetchMessage(sample.id(),"METADATA","id,snippet,sizeEstimate,payload("+partFields(6,false)+")",code+":metadata_snippet_structure");
    row.put("metadata_snippet_structure",summarize(enriched));
    Response structure=fetchMessage(sample.id(),"FULL","id,snippet,sizeEstimate,payload("+partFields(6,false)+")",code+":full_structure_no_data");
    row.put("full_structure_no_data",summarize(structure));
    var parts=parts(structure.node().path("payload"));
    row.put("mime_types",parts.stream().map(p->p.path("mimeType").asText()).filter(v->!v.isEmpty()).distinct().toList());
    row.put("named_parts",parts.stream().filter(p->!p.path("filename").asText().isBlank()).count());
    row.put("calendar_parts",parts.stream().filter(GmailEvidenceProbe::isCalendar).count());
    row.put("image_parts",parts.stream().filter(p->p.path("mimeType").asText().startsWith("image/")).count());
    row.put("over_5mb",meta.node().path("sizeEstimate").asLong()>5000000);
    Set<String> calendarHashes=new HashSet<>();int attachmentReads=0,attachmentPayloads=0;
    if(sample.cohort().equals("calendar"))for(JsonNode part:parts) {
     String aid=part.path("body").path("attachmentId").asText();
     if(isCalendar(part)&&!aid.isBlank()&&part.path("body").path("size").asLong()<=131072&&attachmentReads<2) {
      Response attachment=fetch(gmail.users().messages().attachments().get("me",sample.id(),aid).buildHttpRequest(),code+":calendar_attachment");
      attachmentReads++;if(attachment.status()==200&&addCalendar(attachment.node().path("data").asText(),calendarHashes))attachmentPayloads++;
     }
    }
    row.put("calendar_attachment_requests",attachmentReads);row.put("unique_calendar_payloads_from_attachments",attachmentPayloads);
    boolean bodyCandidate=meta.node().path("sizeEstimate").asLong()>0&&meta.node().path("sizeEstimate").asLong()<=524288&&((sample.cohort().startsWith("brand:")&&bodyBrands<2)||(sample.cohort().equals("calendar")&&bodyCalendars<4));
    if(bodyCandidate) {
     Response body=fetchMessage(sample.id(),"FULL","id,snippet,sizeEstimate,payload("+partFields(6,true)+")",code+":full_body_bounded");row.put("full_body_bounded",summarize(body));
     long textBytes=0;int textParts=0;
     if(body.status()==200&&!body.capped())for(JsonNode part:parts(body.node().path("payload"))) {
      String data=part.path("body").path("data").asText();
      if(isCalendar(part))addCalendar(data,calendarHashes);
      if(part.path("mimeType").asText().startsWith("text/")&&!data.isBlank()){textParts++;textBytes+=decode(data).length;}
     }
     row.put("decoded_text_bytes",textBytes);row.put("inline_text_parts",textParts);
     if(sample.cohort().startsWith("brand:"))bodyBrands++;else bodyCalendars++;
    }
    row.put("unique_calendar_payloads",calendarHashes.size());observations.add(row);System.out.println(code+" "+sample.cohort()+" verified");
   }
   report.put("samples",observations);
   if(!calendars.isEmpty()) {
    Process parser=new ProcessBuilder("/tmp/tidymail-evidence-venv/bin/python",args[2]).redirectError(ProcessBuilder.Redirect.DISCARD).start();
    try(var input=parser.getOutputStream()){JSON.writeValue(input,calendars);}
    byte[] output=parser.getInputStream().readNBytes(131073);int exit=parser.waitFor();
    report.put("calendar_parser_exit",exit);if(exit==0&&output.length<=131072)report.put("calendar_facts",JSON.readTree(output));
   }
   report.put("outcome","completed");
  }catch(Exception error){report.put("outcome","blocked");report.put("safe_error_class",error.getClass().getSimpleName());if(error instanceof OAuth2AuthorizationException auth)report.put("authorization_code",auth.getError().getErrorCode());}
  report.put("requests",requests);report.put("gmail_read_requests",calls);report.put("response_json_bytes",bytesRead);
  Path out=Path.of(args[1]);Files.createDirectories(out.toAbsolutePath().getParent());JSON.writerWithDefaultPrettyPrinter().writeValue(out.toFile(),report);
  System.out.println("Report: "+out+"; outcome="+report.get("outcome")+"; Gmail GET requests="+calls);
  if(!report.get("outcome").equals("completed"))System.exit(2);
 }
 static void addSearch(Map<String,Sample> samples,String cohort,String query,int limit) throws Exception {
  Response r=fetch(gmail.users().messages().list("me").setQ(query).setMaxResults((long)limit).setFields("messages(id),nextPageToken,resultSizeEstimate").buildHttpRequest(),"search:"+cohort);
  if(r.status()==200)for(JsonNode m:r.node().path("messages")){String id=m.path("id").asText();if(!id.isBlank())samples.putIfAbsent(id,new Sample(id,cohort));}
 }
 static Response fetchMessage(String id,String format,String fields,String label)throws Exception{return fetch(gmail.users().messages().get("me",id).setFormat(format).setMetadataHeaders(format.equals("METADATA")?new ArrayList<>(com.tidymail.mail.gmail.GmailMailService.METADATA_HEADERS):null).setFields(fields).buildHttpRequest(),label);}
 static Response fetch(HttpRequest req,String label)throws Exception {
  if(!req.getRequestMethod().equals("GET")||!req.getUrl().getHost().equals("gmail.googleapis.com"))throw new IllegalStateException("Only Gmail GET requests permitted");
  if(calls>=REQUEST_LIMIT||bytesRead>=TOTAL_LIMIT)throw new IllegalStateException("Probe budget reached");calls++;req.setThrowExceptionOnExecuteError(false);long started=System.nanoTime();
  HttpResponse response=req.execute();try {
   int allowance=Math.min(RESPONSE_LIMIT,TOTAL_LIMIT-bytesRead);byte[] bytes=response.getContent().readNBytes(allowance+1);bytesRead+=bytes.length;boolean capped=bytes.length>allowance;
   JsonNode node=!capped&&response.getStatusCode()==200?JSON.readTree(bytes):JSON.createObjectNode();
   long ms=(System.nanoTime()-started)/1000000;requests.add(Map.of("label",label,"status",response.getStatusCode(),"json_bytes",bytes.length,"elapsed_ms",ms,"capped",capped));
   return new Response(node,response.getStatusCode(),bytes.length,ms,capped);
  } finally {response.disconnect();}
 }
 static Map<String,Object> summarize(Response r){var ps=parts(r.node().path("payload"));return Map.of("status",r.status(),"json_bytes",r.bytes(),"elapsed_ms",r.millis(),"capped",r.capped(),"snippet_present",r.node().has("snippet"),"snippet_utf8_bytes",r.node().path("snippet").asText().getBytes(StandardCharsets.UTF_8).length,"payload_present",r.node().has("payload"),"part_count",ps.size(),"body_data_fields",ps.stream().filter(p->!p.path("body").path("data").asText().isBlank()).count());}
 static String partFields(int depth,boolean data){String s="partId,mimeType,filename,headers(name,value),body(size,attachmentId"+(data?",data":"")+")";return depth==0?s:s+",parts("+partFields(depth-1,data)+")";}
 static List<JsonNode> parts(JsonNode p){var out=new ArrayList<JsonNode>();if(p.isObject()){out.add(p);p.path("parts").forEach(child->out.addAll(parts(child)));}return out;}
 static boolean isCalendar(JsonNode p){return p.path("mimeType").asText().equalsIgnoreCase("text/calendar")||p.path("filename").asText().toLowerCase(Locale.ROOT).endsWith(".ics");}
 static byte[] decode(String data){try{return Base64.getUrlDecoder().decode(data);}catch(IllegalArgumentException e){return new byte[0];}}
 static boolean addCalendar(String data,Set<String> hashes)throws Exception{if(data.isBlank())return false;byte[] raw=decode(data);if(raw.length==0||raw.length>131072)return false;String hash=HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(raw));if(!hashes.add(hash))return false;calendars.add(new String(raw,StandardCharsets.UTF_8));return true;}
}

package be.vlaanderen.omgeving.vkbolodapi.service;

import be.vlaanderen.omgeving.vkbolodapi.configuration.JsonldConfiguration;
import be.vlaanderen.omgeving.vkbolodapi.configuration.ReasoningModelConfiguration;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.jsonldjava.core.JsonLdOptions;
import com.github.jsonldjava.core.JsonLdProcessor;
import com.github.jsonldjava.utils.JsonUtils;
import org.apache.jena.rdf.model.InfModel;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.reasoner.Reasoner;
import org.apache.jena.reasoner.rulesys.GenericRuleReasoner;
import org.apache.jena.reasoner.rulesys.Rule;
import org.apache.jena.riot.Lang;
import org.apache.jena.riot.RDFDataMgr;
import org.apache.jena.riot.RDFParser;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 *
 */
@Service
public class OndernemingsService {
    private final RestTemplate restTemplate = new RestTemplate();

    @Autowired
    private JsonldConfiguration jsonldConfiguration;

    @Autowired
    private ReasoningModelConfiguration reasoningModelConfiguration;

    public String getJson(String ondernemingsnr) {
        return extractOriginalJson(ondernemingsnr);
    }

    public String getJsonLd(String ondernemingsnr) {
        Model model = extractModel(ondernemingsnr);
        return rdfToJsonLd(model);
    }

    public String getJsonLd(String originalJson, String ondernemingsnr) {
        Model model = extractModel(originalJson, ondernemingsnr);
        return rdfToJsonLd(model);
    }

    public Model extractModel(String ondernemingsnr) {
        String json = extractJsonLd(ondernemingsnr);
        return parseModelFromJsonLD(json);
    }

    public Model extractModel(String originalJson, String ondernemingsnr) {
        String jsonld = transformToJsonLd(originalJson, ondernemingsnr );
        return parseModelFromJsonLD(jsonld);
    }
    private String transformToJsonLd(String json, String ondernemingsnummer) {
        try {
            ObjectMapper mapper = new ObjectMapper();
            JsonNode root = mapper.readTree(json);

            // Feature ophalen (VKBO heeft altijd 1 element volgens jouw voorbeeld)
            JsonNode feature = root.get("features").get(0);
            JsonNode properties = feature.get("properties");
            JsonNode geometry = feature.get("geometry");

            // JSON-LD @context
            JsonNode context = jsonldConfiguration.getJsonLDContext();

            // --- GEO: WKT POINT ---
            ArrayNode coords = (ArrayNode) geometry.get("coordinates");
            double lon = coords.get(0).asDouble();
            double lat = coords.get(1).asDouble();

            String wkt = "POINT(" + lon + " " + lat + ")";

            // --- JSON-LD root opbouwen ---
            ObjectNode jsonld = mapper.createObjectNode();
            jsonld.set("@context", context);
            jsonld.put("@id", "https://data.vlaanderen.be/id/onderneming/" + ondernemingsnummer);
            jsonld.put("ondernemingsnummer", ondernemingsnummer);
            jsonld.put("type", "org:Organization");


            // --- Organisatiegegevens vanuit VKBO ---
            jsonld.put("maatschappelijkeNaam", properties.get("Maatschappelijke_naam").asText());
            jsonld.put("rechtsvorm", properties.get("Rechtsvorm").asText());
            jsonld.put("rechtstoestand", properties.get("Rechtstoestand").asText());
            jsonld.put("startdatum", properties.get("Startdatum").asText());
            jsonld.put("inschrijvingsdatum", properties.get("Datum_inschrijving").asText());

            // --- Adresobject ---
            ObjectNode adres = mapper.createObjectNode();
            adres.put("@type", "locn:Address");
            adres.put("straat", properties.get("VKBO_Straat").asText());
            adres.put("huisnummer", properties.get("VKBO_Huisnr").asText());
            adres.put("postcode", properties.get("VKBO_Postcode").asText());
            adres.put("gemeente", properties.get("VKBO_Gemeente").asText());
            jsonld.set("adres", adres);

            // --- Geometry object ---
            ObjectNode geomLd = mapper.createObjectNode();
            geomLd.put("@id", "https://data.vlaanderen.be/id/geometry/onderneming/" + ondernemingsnummer);
            geomLd.put("wkt", wkt);
            jsonld.set("geometry", geomLd);

            // --- Identifier object ---
            ObjectNode ident = mapper.createObjectNode();
            ident.put("@id", "https://data.vlaanderen.be/id/identifier/onderneming/" + ondernemingsnummer);
            ident.put("waarde", ondernemingsnummer);
            ident.put("@type", "adms:Identifier");
            jsonld.set("identifier", ident);

            return mapper.writerWithDefaultPrettyPrinter().writeValueAsString(jsonld);
        }
        catch (Exception e) {
            throw new RuntimeException("Failed to transform JSON to JSON-LD", e);
        }
    }

    private String extractJsonLd(String ondernemingsnr) {
        String json = extractOriginalJson(ondernemingsnr);
        return transformToJsonLd(json, ondernemingsnr );
    }

    private String extractOriginalJson(String ondernemingsnr) {
        String url = String.format("https://geo.api.vlaanderen.be/VKBO/ogc/features/v1/collections/Vkbo/items?f=application/json&limit=50&filter-lang=cql-text&filter=Ondernemingsnr eq '%s'", ondernemingsnr);
        HttpHeaders headers = new HttpHeaders();
        headers.set("Accept", "application/json");
        HttpEntity<Void> requestEntity = new HttpEntity<>(headers);

        ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.GET, requestEntity, String.class);
        return response.getBody();
    }

    private String rdfToJsonLd(Model model) {
        StringWriter writer = new StringWriter();
        RDFDataMgr.write(writer, model, Lang.JSONLD);
        return frameJsonLd(writer.toString());
    }

    private String frameJsonLd(String jsonldString) {
        try {
            Object jsonObject = JsonUtils.fromString(jsonldString);
            // Define your frame
            Object frame = jsonldConfiguration.getJsonLDFrame();
            // Frame the JSON-LD
            JsonLdOptions options = new JsonLdOptions();
            Map<String, Object> framed = JsonLdProcessor.frame(jsonObject, frame, options);
            if (framed.containsKey("@graph")) {
                List<?> graph = (List<?>) framed.get("@graph");
                if (graph.size() == 1) {
                    // Promote the node outside of @graph
                    Map<String, Object> singleNode = (Map<String, Object>) graph.get(0);
                    singleNode.put("@context", framed.get("@context"));
                    framed = singleNode;
                }
            }
            return JsonUtils.toPrettyString(framed);
        }
        catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private Model parseModelFromJsonLD(String jsonld) {
        Model model = ModelFactory.createDefaultModel();
        try (InputStream is = new ByteArrayInputStream(jsonld.getBytes(StandardCharsets.UTF_8))) {
            RDFParser.source(is).lang(Lang.JSONLD).parse(model);
        }
        catch (Exception e) {
            throw new RuntimeException("Failed to parse JSON-LD to RDF", e);
        }
        return inferTriples(model, reasoningModelConfiguration.loadTurtleFromClasspath(), reasoningModelConfiguration.getRules());
    }

    public Model inferTriples(Model dataModel, Model ontologyModel, List<Rule> rules) {
        // Combine both models
        Model combinedModel = ModelFactory.createUnion(ontologyModel, dataModel);
        // Apply a reasoner to the combined model
        // Alternative reasoners
        // Reasoner reasoner = ReasonerRegistry.getRDFSReasoner();
        // Reasoner reasoner = ReasonerRegistry.getOWLReasoner();
        Reasoner reasoner = new GenericRuleReasoner(rules);
        reasoner.setDerivationLogging(true);  // optional

        InfModel infModel = ModelFactory.createInfModel(reasoner, combinedModel);
        return infModel.getDeductionsModel().union(dataModel);
    }
}

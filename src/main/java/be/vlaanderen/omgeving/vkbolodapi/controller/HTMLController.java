package be.vlaanderen.omgeving.vkbolodapi.controller;

import be.vlaanderen.omgeving.vkbolodapi.service.OndernemingsService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import org.apache.jena.riot.Lang;
import org.apache.jena.riot.RDFDataMgr;
import org.geotools.geometry.jts.JTS;
import org.geotools.referencing.CRS;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.io.WKTReader;
import org.locationtech.jts.io.WKTWriter;
import org.opengis.referencing.crs.CoordinateReferenceSystem;
import org.opengis.referencing.operation.MathTransform;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.servlet.ViewResolver;

import java.io.StringWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * https://geo.api.vlaanderen.be/capakey/v2/swagger/ui/index
 * http://localhost:8080/id/onderneming/1010353978
 */
@Controller
public class HTMLController {
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Autowired
    List<ViewResolver> viewResolvers;

    @Autowired
    private OndernemingsService ondernemingsService;

    @GetMapping(value = "id/onderneming/{ondernemingsnr}",
                produces = "text/html")
    public String getPerceelAsHtml(
            @PathVariable String ondernemingsnr) {
        return "redirect:/doc/onderneming/{ondernemingsnr}";
    }

    @GetMapping(value = "doc/onderneming/{ondernemingsnr}")
    public String getPerceelDoc(
            @PathVariable String ondernemingsnr,
            Model model) {

        String json = ondernemingsService.getJson(ondernemingsnr);
        String jsonld = ondernemingsService.getJsonLd(json, ondernemingsnr);

        Map<String, Object> jsonAsMap;
        double lon;
        double lat;

        try {
            JsonNode root = objectMapper.readTree(json);

            JsonNode feature = root.get("features").get(0);
            JsonNode props = feature.get("properties");
            ArrayNode coords = (ArrayNode) feature.get("geometry").get("coordinates");

            lon = coords.get(0).asDouble();
            lat = coords.get(1).asDouble();

            jsonAsMap = objectMapper.convertValue(props, Map.class);
        }
        catch (Exception e) {
            throw new RuntimeException("Kon JSON niet verwerken", e);
        }

        // WKT POINT maken (EPSG:4326)
        String wktPoint = "POINT(" + lon + " " + lat + ")";

        model.addAttribute("uri", "http://localhost:8080/id/onderneming/" + ondernemingsnr);
        model.addAttribute("ondernemingsnr", ondernemingsnr);
        model.addAttribute("polygon", wktPoint);
        model.addAttribute("centerX", lon);
        model.addAttribute("centerY", lat);
        model.addAttribute("fields", jsonAsMap);
        model.addAttribute("jsonld", jsonld);

        return "fiche";
    }


}

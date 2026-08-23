package com.javaee.documentservice.security;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.safety.Safelist;
import org.springframework.stereotype.Service;

/** Sanitizes rich document HTML before persistence and before returning legacy content. */
@Service
public class HtmlSanitizationService {

    private final Safelist editorSafelist = Safelist.relaxed()
            .addTags("h1", "h2", "h3", "h4", "h5", "h6", "hr")
            .addAttributes(":all", "class", "title")
            .addAttributes("a", "target", "rel")
            .addAttributes("img", "width", "height", "alt")
            .addAttributes("td", "colspan", "rowspan")
            .addAttributes("th", "colspan", "rowspan")
            .addProtocols("a", "href", "http", "https", "mailto")
            .addProtocols("img", "src", "http", "https", "data");

    private final Document.OutputSettings outputSettings = new Document.OutputSettings()
            .prettyPrint(false);

    public String sanitize(String html) {
        if (html == null || html.isEmpty()) {
            return html;
        }
        return Jsoup.clean(html, "", editorSafelist, outputSettings);
    }
}

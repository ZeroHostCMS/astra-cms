package io.astra.apps.cms;

import io.astra.spi.doc.Document;
import io.astra.spi.hook.DocLifecycleHook;
import jakarta.enterprise.context.ApplicationScoped;

import java.text.Normalizer;
import java.util.Locale;

/** Derives a URL slug from the title when none is set. Demonstrates a Tier 0 lifecycle hook. */
@ApplicationScoped
public class ArticleSlugHook implements DocLifecycleHook {

    @Override
    public String doctype() { return "Article"; }

    @Override
    public void beforeSave(Document doc) {
        String slug = doc.getString("slug");
        if (slug == null || slug.isBlank()) {
            doc.set("slug", toSlug(doc.getString("title")));
        }
    }

    static String toSlug(String title) {
        String ascii = Normalizer.normalize(title, Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "")
                .replace('đ', 'd').replace('Đ', 'D');
        return ascii.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("(^-|-$)", "");
    }
}

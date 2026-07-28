package com.business.discovery.worker.util;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CartSpineScaffoldTest {

    private static final List<String> MECHANISM_FILES = List.of(
            "cart/types.ts", "cart/pricing.ts", "cart/storage.ts", "cart/CartContext.tsx",
            "cart/useCheckout.ts", "cart/index.ts");

    @Test
    void write_emitsTheHeadlessFramework_andNoRenderedUi(@TempDir Path tmp) throws IOException {
        Path src = tmp.resolve("frontend/src");
        CartSpineScaffold.write(src);

        for (String rel : MECHANISM_FILES) {
            assertThat(src.resolve(rel)).as(rel).exists();
        }
        assertThat(src.resolve("cart/cartConfig.ts")).as("config").exists();
        // Headless: the framework ships NO rendered UI — the LLM builds those.
        assertThat(src.resolve("cart/AddToCartButton.tsx")).doesNotExist();
        assertThat(src.resolve("cart/QuantityStepper.tsx")).doesNotExist();
        // and no file imports a UI component (button/sonner) — no presentation is baked in at all.
        for (String rel : MECHANISM_FILES) {
            assertThat(Files.readString(src.resolve(rel)))
                    .as("no baked-in UI in " + rel)
                    .doesNotContain("@/components/ui/")
                    .doesNotContain("from 'sonner'")
                    .doesNotContain("<Toaster");
        }
    }

    @Test
    void mechanismFilesAreFenced_butConfigIsEditable(@TempDir Path tmp) throws IOException {
        Path src = tmp.resolve("frontend/src");
        CartSpineScaffold.write(src);

        for (String rel : MECHANISM_FILES) {
            String first = Files.readString(src.resolve(rel)).lines().findFirst().orElse("");
            assertThat(first).as("fenced: " + rel).contains(CartSpineScaffold.FENCE_MARKER);
        }
        // cartConfig is the business edit point — it must NOT be fenced.
        String cfgFirst = Files.readString(src.resolve("cart/cartConfig.ts")).lines().findFirst().orElse("");
        assertThat(cfgFirst).doesNotContain(CartSpineScaffold.FENCE_MARKER);
    }

    @Test
    void configIsWriteOnce_soBusinessTuningSurvivesRetries(@TempDir Path tmp) throws IOException {
        Path src = tmp.resolve("frontend/src");
        CartSpineScaffold.write(src);

        Path config = src.resolve("cart/cartConfig.ts");
        Files.writeString(config, "// customized by the business\nexport const pricingRules = [];\n");
        CartSpineScaffold.write(src); // simulate a retry

        assertThat(Files.readString(config)).contains("customized by the business");
    }

    @Test
    void solidSeams_areSplitByResponsibility_withFixedApiAndGenericItem(@TempDir Path tmp) throws IOException {
        Path src = tmp.resolve("frontend/src");
        CartSpineScaffold.write(src);

        // ISP/SRP: state, pricing and flow live in separate files.
        String ctx = Files.readString(src.resolve("cart/CartContext.tsx"));
        assertThat(ctx)
                .contains("export const CartProvider")
                .contains("useCart must be used within a CartProvider")
                .contains("addItem").contains("removeItem").contains("setItemQuantity")
                .contains("clearCart").contains("totals").contains("cartCount")
                .contains("computeTotals(cartItems, pricingRules)");   // DIP: rules injected, not inlined

        // OCP: pricing extends via rule factories; the engine is a separate pure function.
        String pricing = Files.readString(src.resolve("cart/pricing.ts"));
        assertThat(pricing)
                .contains("export function computeTotals")
                .contains("percentageFee").contains("flatFee")
                .contains("percentageDiscount").contains("waivedOver");

        // OCP: checkout steps are data, not hardcoded.
        assertThat(Files.readString(src.resolve("cart/useCheckout.ts")))
                .contains("export function useCheckout(steps: CheckoutStep[])")
                .contains("goTo");

        // Generic item type — no restaurant-specific fields; variant + metadata for any vertical.
        assertThat(Files.readString(src.resolve("cart/types.ts")))
                .contains("unitPrice").contains("variantKey").contains("metadata")
                .doesNotContain("menuItemId");

        // DIP: persistence behind an interface with substitutable strategies.
        assertThat(Files.readString(src.resolve("cart/storage.ts")))
                .contains("localStorageCart").contains("memoryCart");
    }
}

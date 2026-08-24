package com.business.discovery.worker.util;

import com.business.discovery.worker.service.llm.FileSpec;
import com.business.discovery.worker.service.llm.PublicFunction;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PlannedComponentPropsCard — fixtures are the real abs-fitness component prop shapes from
 * ARCHITECTURE.json (type-first "GymClassDto gymClass", name:type "isOpen: boolean", function-typed
 * "onEdit: (gymClass: GymClassDto) => void"). The card must normalize all to "name: Type".
 */
class PlannedComponentPropsCardTest {

    private FileSpec component(String path, String name, List<String> params) {
        return FileSpec.builder()
                .fileName(path.substring(path.lastIndexOf('/') + 1))
                .filePath(path).fileType("FRONTEND")
                .publicFunctions(List.of(PublicFunction.builder().name(name).parameters(params).build()))
                .build();
    }

    @Test
    void normalizesTypeFirstParams() {
        var card = PlannedComponentPropsCard.build(List.of(
                component("frontend/src/components/classes/ClassCard.tsx", "ClassCard",
                        List.of("GymClassDto gymClass", "() => void onBookClick"))));

        assertThat(card.toPromptSection()).contains(
                "@/components/classes/ClassCard: { gymClass: GymClassDto; onBookClick: () => void }");
    }

    @Test
    void keepsNameColonTypeAndFunctionTypedParams() {
        var card = PlannedComponentPropsCard.build(List.of(
                component("frontend/src/components/admin/ClassForm.tsx", "ClassForm",
                        List.of("isOpen: boolean", "onClose: () => void", "currentClass?: GymClassDto")),
                component("frontend/src/components/admin/ClassTable.tsx", "ClassTable",
                        List.of("onEdit: (gymClass: GymClassDto) => void"))));

        String s = card.toPromptSection();
        assertThat(s).contains(
                "@/components/admin/ClassForm: { isOpen: boolean; onClose: () => void; currentClass?: GymClassDto }");
        // top-level colon guard: the inner colon inside (gymClass: GymClassDto) must not split the param
        assertThat(s).contains("@/components/admin/ClassTable: { onEdit: (gymClass: GymClassDto) => void }");
    }

    @Test
    void pagesAreIncludedComponentsUiAndNonUiSkipped() {
        var card = PlannedComponentPropsCard.build(List.of(
                component("frontend/src/pages/ClassesPage.tsx", "ClassesPage", List.of()),
                component("frontend/src/components/ui/button.tsx", "Button", List.of("variant: string")),
                FileSpec.builder().filePath("frontend/src/services/classService.ts").fileType("FRONTEND").build()));

        String s = card.toPromptSection();
        assertThat(s).contains("@/pages/ClassesPage: {}");   // page with no props → empty contract
        assertThat(s).doesNotContain("/components/ui/");      // shadcn excluded
        assertThat(s).doesNotContain("classService");         // services excluded
    }

    @Test
    void reportsComponentsThePlanGaveNoProps() {
        FileSpec noProps = FileSpec.builder()
                .filePath("frontend/src/components/classes/ClassCard.tsx").fileType("FRONTEND").build(); // no public_functions
        var card = PlannedComponentPropsCard.build(List.of(noProps));

        assertThat(card.isEmpty()).isTrue();
        assertThat(card.componentsWithoutProps()).contains("frontend/src/components/classes/ClassCard.tsx");
    }

    @Test
    void unwrapsSingleObjectPropsParam() {
        // farmaaish form: the whole props object as one `props: ({ … })` param → unwrap to the fields,
        // so a parent renders <PostForm initialData={…} onSave={…} />, not <PostForm props={{…}} />.
        var card = PlannedComponentPropsCard.build(List.of(
                component("frontend/src/components/admin/blog/PostForm.tsx", "PostForm",
                        List.of("props: ({ initialData?: PostDto; onSave: () => void; onCancel: () => void })")),
                component("frontend/src/components/order/AddToCartButton.tsx", "AddToCartButton",
                        List.of("item: MenuItemDto"))));

        String s = card.toPromptSection();
        assertThat(s).contains(
                "@/components/admin/blog/PostForm: { initialData?: PostDto; onSave: () => void; onCancel: () => void }");
        assertThat(s).doesNotContain("props: (");                              // wrapper removed
        assertThat(s).contains("@/components/order/AddToCartButton: { item: MenuItemDto }"); // genuine single prop kept
    }

    @Test
    void flagsOpaquePropsInterfaceReference() {
        // The abs-fitness failure: enrichment emitted "ClassScheduleProps props" — a bare interface ref
        // the card cannot see inside, so parent/child each invent its fields. Must be flagged as opaque,
        // NOT reported as a healthy contract.
        var card = PlannedComponentPropsCard.build(List.of(
                component("frontend/src/components/classes/ClassSchedule.tsx", "ClassSchedule",
                        List.of("ClassScheduleProps props"))));

        assertThat(card.componentsWithOpaqueProps())
                .contains("frontend/src/components/classes/ClassSchedule.tsx");
        // a genuine data prop typed as a DTO (not <Name>Props) is NOT flagged
        var ok = PlannedComponentPropsCard.build(List.of(
                component("frontend/src/components/order/AddToCartButton.tsx", "AddToCartButton",
                        List.of("item: MenuItemDto"))));
        assertThat(ok.componentsWithOpaqueProps()).isEmpty();
    }

    @Test
    void reconciledInlineFieldsAreNotOpaque() {
        // After SiblingContractReconciler rewrites props to concrete fields, the card transports them
        // cleanly and flags nothing.
        var card = PlannedComponentPropsCard.build(List.of(
                component("frontend/src/components/classes/ClassSchedule.tsx", "ClassSchedule",
                        List.of("classes: FitnessClassDto[]", "onSelectClass: (c: FitnessClassDto) => void"))));

        assertThat(card.componentsWithOpaqueProps()).isEmpty();
        assertThat(card.toPromptSection()).contains(
                "@/components/classes/ClassSchedule: { classes: FitnessClassDto[]; onSelectClass: (c: FitnessClassDto) => void }");
    }

    @Test
    void deterministicSortingAndAliasing() {
        var card = PlannedComponentPropsCard.build(List.of(
                component("frontend/src/components/z/Zeta.tsx", "Zeta", List.of("id: string")),
                component("frontend/src/components/a/Alpha.tsx", "Alpha", List.of("id: string"))));

        assertThat(card.toPromptSection()).containsSubsequence(
                "@/components/a/Alpha", "@/components/z/Zeta");   // sorted by alias
    }
}

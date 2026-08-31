package com.business.discovery.worker.util;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * FrontendHookGenerator — fixtures are the real emitted service shapes from the abs-fitness run
 * (booking: query + single-arg mutation; class: two-arg mutation + void delete; a query with a param).
 */
class FrontendHookGeneratorTest {

    private static final String BOOKING_SERVICE = """
        // GENERATED from the backend API contract — do not edit by hand.
        // One function per endpoint; paths and types are ground truth.

        import apiClient from '@/api/client';
        import type { BookingDto, CreateBookingRequest } from '@/types/booking';

        export const createBooking = async (request: CreateBookingRequest): Promise<BookingDto> => {
          const response = await apiClient.post<BookingDto>('/api/v1/public/bookings', request);
          return response.data;
        };

        export const getMyBookings = async (): Promise<BookingDto[]> => {
          const response = await apiClient.get<BookingDto[]>('/api/v1/public/bookings/my-bookings');
          return response.data;
        };
        """;

    private static final String CLASS_SERVICE = """
        // GENERATED from the backend API contract — do not edit by hand.
        import apiClient from '@/api/client';
        import type { GymClassDto } from '@/types/gym';

        export const getGymClass = async (classId: number): Promise<GymClassDto> => {
          const response = await apiClient.get<GymClassDto>(`/api/v1/public/classes/${classId}`);
          return response.data;
        };

        export const createGymClass = async (request: GymClassDto): Promise<GymClassDto> => {
          const response = await apiClient.post<GymClassDto>('/api/v1/admin/classes', request);
          return response.data;
        };

        export const updateGymClass = async (classId: number, request: GymClassDto): Promise<GymClassDto> => {
          const response = await apiClient.put<GymClassDto>(`/api/v1/admin/classes/${classId}`, request);
          return response.data;
        };

        export const deleteGymClass = async (classId: number): Promise<void> => {
          await apiClient.delete<void>(`/api/v1/admin/classes/${classId}`);
        };
        """;

    private String gen(String path, String content) {
        Optional<FrontendHookGenerator.HookFile> f = FrontendHookGenerator.generate(path, content);
        assertThat(f).isPresent();
        return f.get().content();
    }

    @Test
    void hookPathAndMarker() {
        FrontendHookGenerator.HookFile f =
                FrontendHookGenerator.generate("frontend/src/services/bookingService.ts", BOOKING_SERVICE).orElseThrow();
        assertThat(f.path()).isEqualTo("frontend/src/hooks/bookingHooks.ts");
        assertThat(f.content()).startsWith(FrontendHookGenerator.DERIVED_MARKER);
    }

    @Test
    void queryHookNoParams() {
        String c = gen("frontend/src/services/bookingService.ts", BOOKING_SERVICE);
        assertThat(c).contains(
                "export function useMyBookings(): { data: BookingDto[] | undefined; isLoading: boolean; isError: boolean; error: Error | null }");
        assertThat(c).contains("useQuery({ queryKey: ['booking', 'getMyBookings'], queryFn: getMyBookings })");
    }

    @Test
    void singleArgMutationWrapsAndInvalidates() {
        String c = gen("frontend/src/services/bookingService.ts", BOOKING_SERVICE);
        assertThat(c).contains("export function useCreateBooking(): "
                + "{ mutate: (vars: CreateBookingRequest, options?: MutateOptions<BookingDto, Error, CreateBookingRequest>) => void; "
                + "mutateAsync: (vars: CreateBookingRequest, options?: MutateOptions<BookingDto, Error, CreateBookingRequest>) => Promise<BookingDto>;");
        assertThat(c).contains("mutationFn: (request: CreateBookingRequest) => createBooking(request)");
        assertThat(c).contains("queryClient.invalidateQueries({ queryKey: ['booking'] })");
    }

    @Test
    void twoArgMutationWrapsIntoOneObject() {
        String c = gen("frontend/src/services/classService.ts", CLASS_SERVICE);
        assertThat(c).contains("export function useUpdateGymClass(): "
                + "{ mutate: (vars: { classId: number; request: GymClassDto }, "
                + "options?: MutateOptions<GymClassDto, Error, { classId: number; request: GymClassDto }>) => void;");
        assertThat(c).contains(
                "mutationFn: ({ classId, request }: { classId: number; request: GymClassDto }) => updateGymClass(classId, request)");
    }

    @Test
    void voidDeleteMutation() {
        String c = gen("frontend/src/services/classService.ts", CLASS_SERVICE);
        assertThat(c).contains("export function useDeleteGymClass(): "
                + "{ mutate: (vars: number, options?: MutateOptions<void, Error, number>) => void; "
                + "mutateAsync: (vars: number, options?: MutateOptions<void, Error, number>) => Promise<void>;");
        assertThat(c).contains("mutationFn: (classId: number) => deleteGymClass(classId)");
    }

    @Test
    void mutateAcceptsOptionalOptionsArgSoTwoArgCallsCompile() {
        // Regression for brief 9312afa6 #1: pages call mutate(vars, { onSuccess, onError }); the emitted
        // mutate/mutateAsync must declare the optional MutateOptions second arg or every such call is TS2554.
        String c = gen("frontend/src/services/bookingService.ts", BOOKING_SERVICE);
        assertThat(c).contains("import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';");
        assertThat(c).contains("import type { MutateOptions } from '@tanstack/react-query';");
        assertThat(c).contains("options?: MutateOptions<BookingDto, Error, CreateBookingRequest>) => void;");
        // the runtime body still forwards the real TanStack mutate/mutateAsync unchanged
        assertThat(c).contains("return { mutate, mutateAsync, isPending, isError, error };");
    }

    @Test
    void queryHookWithParam() {
        String c = gen("frontend/src/services/classService.ts", CLASS_SERVICE);
        assertThat(c).contains("export function useGymClass(classId: number): { data: GymClassDto | undefined;");
        assertThat(c).contains("useQuery({ queryKey: ['class', 'getGymClass', classId], queryFn: () => getGymClass(classId) })");
    }

    @Test
    void importsAreScopedToWhatIsUsed() {
        String c = gen("frontend/src/services/classService.ts", CLASS_SERVICE);
        // class file has both a query and mutations → all three react-query symbols
        assertThat(c).contains("import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';");
        assertThat(c).contains("import type { MutateOptions } from '@tanstack/react-query';");
        assertThat(c).contains("import { getGymClass, createGymClass, updateGymClass, deleteGymClass } from '@/services/classService';");
        assertThat(c).contains("import type { GymClassDto } from '@/types/gym';");
    }

    @Test
    void bookingFileOmitsUnusedReactQuerySymbols() {
        String c = gen("frontend/src/services/bookingService.ts", BOOKING_SERVICE);
        // booking has a query and a mutation → useQuery + useMutation + useQueryClient
        assertThat(c).contains("import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';");
        assertThat(c).contains("import type { MutateOptions } from '@tanstack/react-query';");
        assertThat(c).contains("import type { BookingDto, CreateBookingRequest } from '@/types/booking';");
    }

    @Test
    void optionalParamStaysInTypeNotInCallOrKey() {
        // gallery-repo defect: getGallery(section?: GallerySection) — the `?` must stay on the
        // signature/param type, never leak into the call arg or queryKey (section? is a syntax error).
        String svc = """
            // GENERATED from the backend API contract — do not edit by hand.
            import apiClient from '@/api/client';
            import type { GalleryItemDto, GallerySection } from '@/types/gallery';

            export const getGallery = async (section?: GallerySection): Promise<GalleryItemDto[]> => {
              const response = await apiClient.get<GalleryItemDto[]>('/api/v1/gallery');
              return response.data;
            };
            """;
        String c = gen("frontend/src/services/galleryService.ts", svc);
        assertThat(c).contains("export function useGallery(section?: GallerySection):");       // ? in signature
        assertThat(c).contains("queryKey: ['gallery', 'getGallery', section]");                // bare in key
        assertThat(c).contains("queryFn: () => getGallery(section)");                          // bare in call
        assertThat(c).doesNotContain("section?)");                                             // never leaks
    }

    @Test
    void importsServiceLocalExportedType() {
        // media-repo defect: a type the service DEFINES + exports (MediaGalleryFields) used in a param
        // must be imported from the service module, not left dangling (TS2304).
        String svc = """
            // GENERATED from the backend API contract — do not edit by hand.
            import apiClient from '@/api/client';
            import type { MediaAssetDto } from '@/types/media';

            export interface MediaGalleryFields { showInGallery: boolean; section?: string; }

            export const uploadMedia = async (input: MediaGalleryFields): Promise<MediaAssetDto> => {
              const response = await apiClient.post<MediaAssetDto>('/api/v1/admin/media', input);
              return response.data;
            };
            """;
        String c = gen("frontend/src/services/mediaService.ts", svc);
        assertThat(c).contains("import type { MediaGalleryFields } from '@/services/mediaService';");
        assertThat(c).contains("mutationFn: (input: MediaGalleryFields) => uploadMedia(input)");
    }

    @Test
    void emptyServiceYieldsNoHookFile() {
        assertThat(FrontendHookGenerator.generate("frontend/src/services/emptyService.ts",
                "// GENERATED from the backend API contract\nimport apiClient from '@/api/client';\n")).isEmpty();
    }
}

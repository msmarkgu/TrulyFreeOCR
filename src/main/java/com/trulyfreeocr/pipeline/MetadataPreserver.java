package com.trulyfreeocr.pipeline;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.IdentityHashMap;
import java.util.List;

import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSObject;
import org.apache.pdfbox.cos.COSStream;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDDocumentInformation;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDMetadata;
import org.apache.pdfbox.pdmodel.documentinterchange.logicalstructure.PDMarkInfo;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotation;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.outline.PDDocumentOutline;

/**
 * Copies metadata from a source PDF to an output PDF.
 *
 * Preserved elements:
 *   - PDDocumentInformation (title, author, subject, keywords, creator, producer)
 *   - PDDocumentOutline (bookmark tree) — COS-level deep copy
 *   - Per-page PDAnnotation lists — each annotation COS dictionary is duplicated
 *   - XML metadata stream (XMP)
 *
 * Limitations:
 *   - Annotation page references (e.g. link destinations) are not remapped.
 *   - Named destinations are preserved but may reference missing pages.
 *   - Outline entries pointing to specific pages are kept but not remapped
 *     (the output pages are in the same order, so page-number-based links
 *     generally still work).
 */
public class MetadataPreserver {

    /**
     * Copies all metadata from source to output.
     *
     * @param source      The original PDF.
     * @param output      The newly assembled PDF.
     * @param outputPages Output pages indexed to match source pages.
     */
    public void preserve(PDDocument source, PDDocument output, List<PDPage> outputPages) throws IOException {
        copyDocumentInfo(source, output);
        copyOutline(source, output);
        copyAnnotations(source, outputPages);
        copyXmlMetadata(source, output);
    }

    private void copyDocumentInfo(PDDocument source, PDDocument output) {
        PDDocumentInformation info = source.getDocumentInformation();
        if (info != null) {
            PDDocumentInformation dest = new PDDocumentInformation();
            copyIfSet(info::getTitle, dest::setTitle);
            copyIfSet(info::getAuthor, dest::setAuthor);
            copyIfSet(info::getSubject, dest::setSubject);
            copyIfSet(info::getKeywords, dest::setKeywords);
            copyIfSet(info::getCreator, dest::setCreator);
            copyIfSet(info::getProducer, dest::setProducer);
            output.setDocumentInformation(dest);
        }
    }

    private void copyIfSet(ThrowingSupplier<String> getter, ThrowingConsumer<String> setter) {
        try {
            String val = getter.get();
            if (val != null) setter.accept(val);
        } catch (Exception e) {
            // skip
        }
    }

    @FunctionalInterface
    private interface ThrowingSupplier<T> {
        T get() throws Exception;
    }

    @FunctionalInterface
    private interface ThrowingConsumer<T> {
        void accept(T t) throws Exception;
    }

    private void copyOutline(PDDocument source, PDDocument output) throws IOException {
        PDDocumentOutline srcOutline = source.getDocumentCatalog().getDocumentOutline();
        if (srcOutline == null) return;

        // Deep-copy the outline COS dictionary tree
        COSDictionary srcDict = srcOutline.getCOSObject();
        COSDictionary dstDict = deepCopyCOSDictionary(srcDict);
        PDDocumentOutline dstOutline = new PDDocumentOutline(dstDict);
        output.getDocumentCatalog().setDocumentOutline(dstOutline);
    }

    private void copyAnnotations(PDDocument source, List<PDPage> outputPages) throws IOException {
        int pageCount = Math.min(source.getNumberOfPages(), outputPages.size());
        for (int i = 0; i < pageCount; i++) {
            PDPage srcPage = source.getPage(i);
            PDPage dstPage = outputPages.get(i);

            List<PDAnnotation> annotations = srcPage.getAnnotations();
            if (annotations.isEmpty()) continue;

            COSArray dstAnnots = new COSArray();
            for (PDAnnotation ann : annotations) {
                COSDictionary cloned = deepCopyCOSDictionary(ann.getCOSObject());
                // Strip /P (parent page) — the cloned annotation dictionary should not
                // carry a reference back to the source page.
                cloned.removeItem(COSName.P);
                dstAnnots.add(cloned);
            }
            dstPage.getCOSObject().setItem(COSName.ANNOTS, dstAnnots);
        }
    }

    private void copyXmlMetadata(PDDocument source, PDDocument output) throws IOException {
        PDMetadata srcMeta = source.getDocumentCatalog().getMetadata();
        if (srcMeta == null) return;

        try (var in = srcMeta.createInputStream()) {
            byte[] data = in.readAllBytes();
            PDMetadata dstMeta = new PDMetadata(output);
            dstMeta.importXMPMetadata(data);
            output.getDocumentCatalog().setMetadata(dstMeta);
        } catch (Exception e) {
            // Non-critical — skip XML metadata copy on failure
        }
    }

    private static COSDictionary deepCopyCOSDictionary(COSDictionary original) {
        return deepCopyCOSDictionary(original, new IdentityHashMap<>());
    }

    private static COSStream deepCopyCOSStream(COSStream original) {
        return deepCopyCOSStream(original, new IdentityHashMap<>());
    }

    private static COSArray deepCopyCOSArray(COSArray original) {
        return deepCopyCOSArray(original, new IdentityHashMap<>());
    }

    private static COSDictionary deepCopyCOSDictionary(COSDictionary original,
                                                        IdentityHashMap<COSBase, COSBase> visited) {
        COSBase existing = visited.get(original);
        if (existing instanceof COSDictionary d) return d;

        COSDictionary copy = new COSDictionary();
        visited.put(original, copy);
        for (var entry : original.entrySet()) {
            COSName key = entry.getKey();
            copy.setItem(key, deepCopyValue(entry.getValue(), visited));
        }
        return copy;
    }

    private static COSStream deepCopyCOSStream(COSStream original,
                                                IdentityHashMap<COSBase, COSBase> visited) {
        COSBase existing = visited.get(original);
        if (existing instanceof COSStream s) return s;

        COSStream copy = new COSStream();
        visited.put(original, copy);
        for (var entry : original.entrySet()) {
            COSName key = entry.getKey();
            if (COSName.LENGTH.equals(key)) continue;
            copy.setItem(key, deepCopyValue(entry.getValue(), visited));
        }
        try (InputStream in = original.createInputStream();
             OutputStream out = copy.createOutputStream()) {
            in.transferTo(out);
        } catch (IOException e) {
            // skip — stream data could not be copied
        }
        return copy;
    }

    private static COSArray deepCopyCOSArray(COSArray original,
                                              IdentityHashMap<COSBase, COSBase> visited) {
        COSBase existing = visited.get(original);
        if (existing instanceof COSArray a) return a;

        COSArray copy = new COSArray();
        visited.put(original, copy);
        for (var value : original) {
            copy.add(deepCopyValue(value, visited));
        }
        return copy;
    }

    /**
     * Deep-copies a COS value.  COSObject indirect references are left as-is
     * because they are internal to the metadata tree being copied (outline
     * items reference each other via COSObject).  Cross-document references
     * to page objects are handled by stripping known page-dependent keys
     * (e.g. /P from annotation dictionaries) in the caller.
     */
    private static COSBase deepCopyValue(COSBase value,
                                          IdentityHashMap<COSBase, COSBase> visited) {
        if (value instanceof COSStream stream) {
            return deepCopyCOSStream(stream, visited);
        }
        if (value instanceof COSDictionary dict) {
            return deepCopyCOSDictionary(dict, visited);
        }
        if (value instanceof COSArray arr) {
            return deepCopyCOSArray(arr, visited);
        }
        // COSObject (indirect references) and primitive COS types are left
        // as-is — they are immutable value objects or references valid within
        // the copied subtree.
        return value;
    }
}

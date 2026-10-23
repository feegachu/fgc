package com.susukkang.fgc.common.storage;

import com.susukkang.fgc.common.exception.FgcBusinessException;
import com.susukkang.fgc.common.exception.FgcErrorCode;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSName;
import org.apache.poi.hssf.usermodel.HSSFWorkbook;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import javax.imageio.ImageIO;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipInputStream;

/** 합성 문서 입력만 허용하는 보수적 정책. 안티바이러스 검사를 대체하지 않는다. */
@Component
public class UploadFileValidator {
    private static final Map<String, String> TYPES = Map.of(
            "pdf", "application/pdf", "xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
            "xls", "application/vnd.ms-excel", "csv", "text/csv", "png", "image/png",
            "jpg", "image/jpeg", "jpeg", "image/jpeg");
    private static final Set<String> PDF_ACTIVE = Set.of(
            "JS", "JavaScript", "AA", "OpenAction", "Launch", "EmbeddedFiles", "EmbeddedFile", "XFA",
            "RichMedia", "SubmitForm", "ImportData", "GoToR", "GoToE");
    private final long maxBytes;

    public UploadFileValidator(FileStorageProperties properties) {
        maxBytes = properties.maxFileBytes();
    }

    public ValidatedFile validate(MultipartFile file) {
        if (file == null || file.isEmpty() || file.getSize() > maxBytes) throw invalid();
        String name = file.getOriginalFilename();
        if (name == null || name.isBlank() || name.length() > 255 || name.chars().anyMatch(Character::isISOControl)
                || name.matches(".*[<>:\"/\\\\|?*].*")
                || name.endsWith(".") || name.endsWith(" ")) throw invalid();
        int dot = name.lastIndexOf('.');
        String extension = dot < 1 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
        String media = TYPES.get(extension);
        String declared = file.getContentType() == null ? "" : file.getContentType().split(";", 2)[0].trim().toLowerCase(Locale.ROOT);
        boolean csvAlias = extension.equals("csv") && Set.of("application/csv", "application/vnd.ms-excel").contains(declared);
        if (media == null || !(media.equals(declared) || csvAlias)) throw invalid();
        try (var in = file.getInputStream()) {
            byte[] bytes = in.readNBytes(Math.toIntExact(maxBytes + 1));
            if (bytes.length == 0 || bytes.length > maxBytes || bytes.length != file.getSize()) throw invalid();
            switch (extension) {
                case "pdf" -> validatePdf(bytes);
                case "xlsx", "xls" -> validateWorkbook(bytes, extension);
                case "csv" -> validateCsv(bytes);
                default -> validateImage(bytes, media);
            }
            String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
            return new ValidatedFile(name, media, bytes, hash);
        } catch (FgcBusinessException ex) {
            throw ex;
        } catch (Exception ex) {
            // 파서 메시지에는 서버 경로·문서 내용이 포함될 수 있어 사용자·로그에 전달하지 않는다.
            throw invalid();
        }
    }

    private void validatePdf(byte[] bytes) throws IOException {
        if (bytes.length < 5 || !new String(bytes, 0, 5, StandardCharsets.US_ASCII).equals("%PDF-")) throw invalid();
        try (var document = Loader.loadPDF(bytes)) {
            if (document.isEncrypted() || document.getNumberOfPages() == 0) throw invalid();
            var pending = new java.util.ArrayDeque<COSBase>();
            var seen = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<COSBase, Boolean>());
            pending.add(document.getDocumentCatalog().getCOSObject());
            while (!pending.isEmpty()) {
                COSBase value = pending.removeLast();
                if (!seen.add(value)) continue;
                if (seen.size() > 100_000) throw invalid();
                if (value instanceof org.apache.pdfbox.cos.COSObject object) {
                    if (object.getObject() != null) pending.add(object.getObject());
                } else if (value instanceof org.apache.pdfbox.cos.COSArray array) {
                    for (COSBase item : array) pending.add(item);
                }
                if (value instanceof COSDictionary dictionary) {
                    for (COSName key : dictionary.keySet()) {
                        if (PDF_ACTIVE.contains(key.getName())) throw invalid();
                    }
                    COSBase action = dictionary.getDictionaryObject(COSName.S);
                    if (action instanceof COSName actionName && PDF_ACTIVE.contains(actionName.getName())) throw invalid();
                    pending.addAll(dictionary.getValues());
                }
            }
        }
    }

    private void validateWorkbook(byte[] bytes, String extension) throws Exception {
        if (extension.equals("xlsx")) validateZip(bytes);
        try (var workbook = WorkbookFactory.create(new ByteArrayInputStream(bytes))) {
            if ((extension.equals("xlsx") && !(workbook instanceof XSSFWorkbook))
                    || (extension.equals("xls") && !(workbook instanceof HSSFWorkbook))) throw invalid();
            if (workbook instanceof XSSFWorkbook xssf && (!xssf.getExternalLinksTable().isEmpty() || xssf.isMacroEnabled())) throw invalid();
            if (workbook instanceof XSSFWorkbook xssf) {
                for (var part : xssf.getPackage().getParts()) if (!part.isRelationshipPart()) {
                    for (var relationship : part.getRelationships()) {
                        if (relationship.getTargetMode() == org.apache.poi.openxml4j.opc.TargetMode.EXTERNAL) throw invalid();
                    }
                }
            }
            if (workbook instanceof HSSFWorkbook hssf) {
                for (var entry : hssf.getDirectory()) {
                    String entryName = entry.getName();
                    if (!Set.of("Workbook", "Book", "\005SummaryInformation", "\005DocumentSummaryInformation").contains(entryName)) throw invalid();
                }
            }
            // 입력 보조는 값만 받는다. 매크로·DDE·외부 참조·계산식은 실행하지도 보관하지도 않는다.
            if (workbook.getNumberOfNames() > 0) throw invalid();
            long cells = 0;
            for (var sheet : workbook) for (var row : sheet) for (var cell : row) {
                if (++cells > 1_000_000 || cell.getCellType() == CellType.FORMULA) throw invalid();
            }
        }
    }

    private void validateZip(byte[] bytes) throws IOException {
        long expanded = 0;
        int entries = 0;
        boolean workbook = false;
        var seen = new java.util.HashSet<String>();
        try (var zip = new ZipInputStream(new ByteArrayInputStream(bytes))) {
            java.util.zip.ZipEntry entry;
            byte[] buffer = new byte[8192];
            while ((entry = zip.getNextEntry()) != null) {
                String name = entry.getName();
                String lower = name.toLowerCase(Locale.ROOT);
                if (++entries > 4096 || !seen.add(name) || name.startsWith("/") || name.contains("\\")
                        || name.contains("..") || lower.contains("vbaproject") || lower.contains("embeddings/")
                        || lower.contains("externallinks/") || lower.contains("macrosheets/")) throw invalid();
                workbook |= name.equals("xl/workbook.xml");
                int count;
                while ((count = zip.read(buffer)) != -1) {
                    expanded += count;
                    if (expanded > 64L * 1024 * 1024) throw invalid();
                }
            }
        }
        if (!workbook) throw invalid();
    }

    private void validateCsv(byte[] bytes) throws Exception {
        String text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
        if (text.startsWith("\uFEFF")) text = text.substring(1);
        if (text.chars().anyMatch(c -> Character.isISOControl(c) && c != '\r' && c != '\n' && c != '\t')) throw invalid();
        // CSV 수식 주입 차단. 부호 있는 숫자는 원본 금액이므로 허용한다.
        var field = new StringBuilder();
        boolean quoted = false;
        boolean closedQuote = false;
        for (int i = 0; i < text.length(); i++) {
            char character = text.charAt(i);
            if (quoted) {
                if (character == '"') {
                    if (i + 1 < text.length() && text.charAt(i + 1) == '"') { field.append('"'); i++; }
                    else { quoted = false; closedQuote = true; }
                } else field.append(character);
            } else if (character == ',' || character == ';' || character == '\r' || character == '\n') {
                validateCsvField(field.toString());
                field.setLength(0);
                closedQuote = false;
            } else if (character == '"') {
                if (closedQuote || !field.toString().isBlank()) throw invalid();
                field.setLength(0);
                quoted = true;
            } else {
                if (closedQuote && !Character.isWhitespace(character)) throw invalid();
                field.append(character);
            }
        }
        if (quoted) throw invalid();
        validateCsvField(field.toString());
    }

    private void validateCsvField(String field) {
        String value = field.stripLeading();
        if (!value.isEmpty() && "=+@-".indexOf(value.charAt(0)) >= 0
                && !value.stripTrailing().matches("[+-]?[0-9][0-9,.]*")) throw invalid();
    }

    private void validateImage(byte[] bytes, String media) throws IOException {
        try (var stream = ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
            var readers = ImageIO.getImageReaders(stream);
            if (!readers.hasNext()) throw invalid();
            var reader = readers.next();
            try {
                reader.setInput(stream);
                String format = reader.getFormatName().toLowerCase(Locale.ROOT);
                if (!(media.equals("image/png") ? format.equals("png") : format.equals("jpeg"))) throw invalid();
                if ((long) reader.getWidth(0) * reader.getHeight(0) > 20_000_000L) throw invalid();
                if (reader.read(0) == null) throw invalid();
            } finally {
                reader.dispose();
            }
        }
    }

    private FgcBusinessException invalid() {
        return new FgcBusinessException(FgcErrorCode.INTK_001, Map.of("max", maxBytes / 1024 / 1024));
    }

    public record ValidatedFile(String originalName, String mediaType, byte[] bytes, String sha256) { }
}

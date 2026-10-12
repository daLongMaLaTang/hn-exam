package com.huaneng.shanyin.hnexam.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.huaneng.shanyin.hnexam.domain.QuestionStatus;
import com.huaneng.shanyin.hnexam.domain.QuestionType;
import com.huaneng.shanyin.hnexam.entity.Question;
import com.huaneng.shanyin.hnexam.repository.QuestionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.w3c.dom.*;
import org.xml.sax.InputSource;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;
import java.util.zip.ZipFile;

@Component
@RequiredArgsConstructor
@Slf4j
public class QuestionBankDocxImporter {
    private static final String SOURCE_PREFIX = "华能山阴发电有限责任公司安规考试题库";
    private static final Pattern QUESTION_START = Pattern.compile("^\\s*(\\d+)[、.．]\\s*(.+)$", Pattern.DOTALL);
    private static final Pattern OPTION_MARK = Pattern.compile("(?<![A-Za-z])([A-H])[、.．]");
    private static final Pattern ANSWER_MARK = Pattern.compile("答案\\s*[：:]");
    private static final Pattern CHOICE_ANSWER = Pattern.compile(
            "^\\s*[（(]?\\s*([A-H](?:\\s*[,，、;；/\\\\+]?\\s*[A-H])*)\\s*[）)]?(?=\\s|[。.．、，,;；]|$)",
            Pattern.CASE_INSENSITIVE);
    private final QuestionRepository questions;
    private final ObjectMapper json;

    @Value("${app.bootstrap.question-bank-path:}")
    private String configuredPath;

    public void importIfNeeded() {
        if (configuredPath == null || configuredPath.isBlank()) return;
        Path path = Path.of(configuredPath);
        if (!Files.isRegularFile(path)) {
            log.info("题库源文件不存在，跳过首次导入：{}", path);
            return;
        }
        try {
            List<Question> parsed = parse(path);
            Set<String> existing = questions.findBySourceStartingWith(SOURCE_PREFIX).stream().map(Question::getSource).collect(java.util.stream.Collectors.toSet());
            List<Question> imported = parsed.stream().filter(q -> !existing.contains(q.getSource())).toList();
            questions.saveAll(imported);
            long total = existing.size() + imported.size();
            long active = parsed.stream().filter(q -> q.getStatus() == QuestionStatus.ACTIVE).count();
            log.info("安规题库同步完成：源文档识别 {} 道，本次新增 {} 道，可直接组卷 {} 道，其余待审核", total, imported.size(), active);
        } catch (Exception e) {
            log.error("安规题库导入失败，系统仍可启动并由管理员手工导入", e);
        }
    }

    List<Question> parse(Path path) throws Exception {
        List<String> lines = readParagraphs(path);
        List<Question> result = new ArrayList<>();
        QuestionType section = null;
        List<String> block = new ArrayList<>();
        int sourceNumber = 0;
        for (String line : lines) {
            QuestionType nextSection = heading(line);
            if (nextSection != null) {
                addBlock(result, section, sourceNumber, block);
                block.clear(); section = nextSection; sourceNumber = 0; continue;
            }
            Matcher start = QUESTION_START.matcher(line);
            if (section != null && start.matches()) {
                addBlock(result, section, sourceNumber, block);
                block.clear(); sourceNumber = Integer.parseInt(start.group(1)); block.add(start.group(2).trim());
            } else if (!block.isEmpty()) block.add(line.trim());
        }
        addBlock(result, section, sourceNumber, block);
        return result;
    }

    private void addBlock(List<Question> result, QuestionType section, int number, List<String> block) {
        if (section == null || number == 0 || block.isEmpty()) return;
        int answerIndex = -1, answerStart = -1, answerOffset = -1;
        for (int i = 0; i < block.size(); i++) {
            Matcher marker = ANSWER_MARK.matcher(block.get(i));
            if (marker.find()) { answerIndex = i; answerStart = marker.start(); answerOffset = marker.end(); break; }
        }
        if (answerIndex < 0) return;
        String rawAnswer = block.get(answerIndex).substring(answerOffset).trim();
        if (rawAnswer.isBlank()) return;
        List<String> content = new ArrayList<>(block.subList(0, answerIndex));
        String inlinePrefix = block.get(answerIndex).substring(0, answerStart).trim();
        if (!inlinePrefix.isBlank()) content.add(inlinePrefix);
        if (content.isEmpty()) return;
        String stem = content.get(0).trim();
        Question q = new Question();
        q.setStem(stem); q.setExplanation(""); q.setCourseCode("安全规程"); q.setChapter("未分章"); q.setDifficulty(3);
        q.setSource(SOURCE_PREFIX + " · " + sectionName(section) + "第" + number + "题");
        q.setStatus(QuestionStatus.PENDING_REVIEW);

        if (section == QuestionType.SINGLE_CHOICE || section == QuestionType.MULTIPLE_CHOICE) {
            String allContent = String.join(" ", content);
            Matcher firstOption = OPTION_MARK.matcher(allContent);
            String optionText = firstOption.find() ? allContent.substring(firstOption.start()) : "";
            if (firstOption.find(0)) q.setStem(allContent.substring(0, firstOption.start()).trim());
            LinkedHashMap<String,String> options = splitOptions(optionText);
            List<String> answerKeys = choiceAnswerKeys(rawAnswer);
            List<String> answers = answerKeys.stream().map(options::get).filter(Objects::nonNull).toList();
            boolean multiple = section == QuestionType.MULTIPLE_CHOICE || answerKeys.size() > 1;
            q.setType(multiple ? QuestionType.MULTIPLE_CHOICE : QuestionType.SINGLE_CHOICE);
            try { q.setOptionsJson(json.writeValueAsString(options.values())); } catch (Exception e) { return; }
            q.setAnswer(answers.isEmpty() ? rawAnswer : String.join(",", answers));
            boolean answerCountValid = multiple ? answerKeys.size() >= 2 : answerKeys.size() == 1;
            if (options.size() >= 2 && answerCountValid && answers.size() == answerKeys.size()) q.setStatus(QuestionStatus.ACTIVE);
        } else if (section == QuestionType.TRUE_FALSE) {
            q.setStem(String.join(" ", content).trim()); q.setType(QuestionType.TRUE_FALSE); q.setOptionsJson("[\"正确\",\"错误\"]");
            if (rawAnswer.contains("√") || rawAnswer.equalsIgnoreCase("正确") || rawAnswer.equalsIgnoreCase("对")) { q.setAnswer("正确"); q.setStatus(QuestionStatus.ACTIVE); }
            else if (rawAnswer.contains("×") || rawAnswer.contains("✕") || rawAnswer.equalsIgnoreCase("错误") || rawAnswer.equalsIgnoreCase("错")) { q.setAnswer("错误"); q.setStatus(QuestionStatus.ACTIVE); }
            else q.setAnswer(rawAnswer);
        } else {
            q.setStem(String.join(" ", content).trim()); q.setType(section); q.setOptionsJson(null); q.setAnswer(rawAnswer);
        }
        result.add(q);
    }

    private List<String> choiceAnswerKeys(String rawAnswer) {
        Matcher matcher = CHOICE_ANSWER.matcher(rawAnswer);
        if (!matcher.find()) return List.of();
        return matcher.group(1).toUpperCase(Locale.ROOT).chars()
                .filter(c -> c >= 'A' && c <= 'H')
                .mapToObj(c -> String.valueOf((char) c))
                .distinct()
                .toList();
    }

    private LinkedHashMap<String,String> splitOptions(String text) {
        LinkedHashMap<String,String> values = new LinkedHashMap<>();
        Matcher matcher = OPTION_MARK.matcher(text);
        List<String> keys = new ArrayList<>(); List<Integer> starts = new ArrayList<>(); List<Integer> ends = new ArrayList<>();
        while (matcher.find()) { keys.add(matcher.group(1)); starts.add(matcher.start()); ends.add(matcher.end()); }
        for (int i = 0; i < keys.size(); i++) {
            int end = i + 1 < keys.size() ? starts.get(i + 1) : text.length();
            String value = text.substring(ends.get(i), end).trim().replaceAll("\\s+", " ");
            if (!value.isBlank()) values.put(keys.get(i), value);
        }
        return values;
    }

    private QuestionType heading(String line) {
        if (line.matches("^[一二三四五六七八九十]+、填空题.*")) return QuestionType.FILL_BLANK;
        if (line.matches("^[一二三四五六七八九十]+、(多选题|多项选择题).*")) return QuestionType.MULTIPLE_CHOICE;
        if (line.matches("^[一二三四五六七八九十]+、(选择题|单选题|单项选择题).*")) return QuestionType.SINGLE_CHOICE;
        if (line.matches("^[一二三四五六七八九十]+、判断题.*")) return QuestionType.TRUE_FALSE;
        if (line.matches("^[一二三四五六七八九十]+、简答题.*")) return QuestionType.SHORT_ANSWER;
        if (line.matches("^[一二三四五六七八九十]+、(大题|论述题|综合题|案例分析题).*")) return QuestionType.ESSAY;
        return null;
    }

    private String sectionName(QuestionType type) { return switch (type) {
        case FILL_BLANK -> "填空题"; case SINGLE_CHOICE -> "选择题"; case MULTIPLE_CHOICE -> "多选题"; case TRUE_FALSE -> "判断题"; case SHORT_ANSWER -> "简答题"; default -> "试题";
    }; }

    private List<String> readParagraphs(Path path) throws Exception {
        try (ZipFile zip = new ZipFile(path.toFile())) {
            var entry = zip.getEntry("word/document.xml");
            if (entry == null) throw new IOException("DOCX 中缺少 document.xml");
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, ""); factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            Document document = factory.newDocumentBuilder().parse(new InputSource(zip.getInputStream(entry)));
            NodeList paragraphs = document.getElementsByTagNameNS("http://schemas.openxmlformats.org/wordprocessingml/2006/main", "p");
            List<String> lines = new ArrayList<>();
            for (int i = 0; i < paragraphs.getLength(); i++) {
                Element p = (Element) paragraphs.item(i);
                StringBuilder line = new StringBuilder();
                appendParagraphText(p, line);
                for (String part : line.toString().split("\\R")) {
                    if (!part.isBlank()) lines.add(part.trim());
                }
            }
            return lines;
        }
    }

    private void appendParagraphText(Node node, StringBuilder target) {
        if ("t".equals(node.getLocalName())) target.append(node.getTextContent());
        else if ("br".equals(node.getLocalName()) || "cr".equals(node.getLocalName())) target.append('\n');
        else if ("tab".equals(node.getLocalName())) target.append(' ');
        else for (Node child = node.getFirstChild(); child != null; child = child.getNextSibling()) appendParagraphText(child, target);
    }
}

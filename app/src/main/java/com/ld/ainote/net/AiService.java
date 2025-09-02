package com.ld.ainote.net;

import android.util.Log;
import androidx.annotation.Nullable;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

import com.ld.ainote.BuildConfig; // 需在 module 的 build.gradle 中有 buildConfigField OPENAI_API_KEY

public class AiService {

    public interface Callback {
        void onSuccess(String out);
        void onError(Exception e);
    }

    // 相容舊呼叫
    public static void ask(String task, String text, int n, Callback cb) {
        ask(task, text, n, null, cb);
    }

    // 新版：可帶 age，出題時依年齡調整難度
    public static void ask(String task, String text, int n, @Nullable Integer age, Callback cb) {
        new Thread(() -> {
            try {
                String prompt = buildPrompt(task, text, n, age);
                String result = callOpenAI(prompt);
                cb.onSuccess(result);
            } catch (Exception e) {
                cb.onError(e);
            }
        }).start();
    }

    private static String buildPrompt(String task, String text, int n, @Nullable Integer age) {
        String userAge = (age == null) ? "未指定" : (age + " 歲");
        switch (task) {
            case "summary":
                return "你是專業的讀書助教。請閱讀使用者的筆記文字，輸出「條列式重點整理」。" +
                        "\n- 語言用繁體中文。" +
                        "\n- 每點 1~2 句，避免冗長。" +
                        "\n- 若文中有 [[這樣的標記]] 視為特別重要，請優先呈現。" +
                        "\n\n【筆記內容】\n" + text;

            case "integrate":
                return "你是學習教練。請閱讀筆記並做「融會貫通」說明，包含：概念關聯、實務應用、常見誤解與修正、延伸思考題。" +
                        "\n- 語言用繁體中文。" +
                        "\n- 以段落小標題分區，條列為主。" +
                        "\n- 若文中有 [[這樣的標記]] 視為重點，請適度引用。" +
                        "\n- 讀者年齡：" + userAge +
                        "\n\n【筆記內容】\n" + text;

            case "quiz":
            default:
                // 依年齡調整出題風格
                String ageGuide;
                if (age == null) {
                    ageGuide = "難度以一般大學生為準；詞彙中等、題型兼具選擇與簡答。";
                } else if (age <= 10) {
                    ageGuide = "使用淺白詞彙與生活化例子；多用四選一選擇題；每題後附一句提示。";
                } else if (age <= 15) {
                    ageGuide = "詞彙簡潔、概念清楚；選擇題與是非題為主；偶有簡答題。";
                } else if (age <= 18) {
                    ageGuide = "混合選擇題與短答題，著重理解與應用。";
                } else if (age <= 25) {
                    ageGuide = "偏向理解與應用的短答/情境題；必要時附關鍵術語。";
                } else {
                    ageGuide = "可加入實務情境與批判思考題；題幹精煉，術語完整。";
                }

                return "你是出題老師。請根據筆記內容出 " + Math.max(1, n) + " 題練習題（繁體中文）。" +
                        "\n- 題目難度需依讀者年齡調整：" + userAge +
                        "\n- 出題風格指南：" + ageGuide +
                        "\n- 若筆記含有 [[這樣的標記]]，題目要優先圍繞其重點。" +
                        "\n- 請用「Q1, Q2, ...」編號，必要時附答案或解析（簡短）。" +
                        "\n\n【筆記內容】\n" + text;
        }
    }

    private static String callOpenAI(String prompt) throws Exception {
        URL url = new URL("https://api.openai.com/v1/chat/completions");
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("POST");
        conn.setReadTimeout(60000);
        conn.setConnectTimeout(60000);
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setRequestProperty("Authorization", "Bearer " + BuildConfig.OPENAI_API_KEY);
        conn.setDoOutput(true);

        JSONObject req = new JSONObject();
        req.put("model", "gpt-4o-mini");

        JSONArray messages = new JSONArray();
        messages.put(new JSONObject().put("role", "system").put("content", "你是一位中文學習助理。"));
        messages.put(new JSONObject().put("role", "user").put("content", prompt));
        req.put("messages", messages);
        req.put("temperature", 0.7);

        try(OutputStream os = conn.getOutputStream()) {
            os.write(req.toString().getBytes(StandardCharsets.UTF_8));
        }

        int code = conn.getResponseCode();
        InputStream is = (code >= 200 && code < 300) ? conn.getInputStream() : conn.getErrorStream();
        String body;
        try(BufferedReader br = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while((line = br.readLine()) != null) sb.append(line);
            body = sb.toString();
        }

        if (code < 200 || code >= 300) {
            Log.e("AiService", "OpenAI error: " + body);
            throw new IOException("OpenAI HTTP " + code);
        }

        JSONObject json = new JSONObject(body);
        String out = json.getJSONArray("choices")
                .getJSONObject(0)
                .getJSONObject("message")
                .getString("content");
        return out.trim();
    }
}

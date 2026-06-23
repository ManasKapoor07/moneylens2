package com.moneylens.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.moneylens.dto.CopilotMessageDto;
import com.moneylens.dto.budget.BudgetJson;
import com.moneylens.dto.profile.MonthlyProfileJson;
import com.moneylens.dto.profile.OverallProfileJson;
import com.moneylens.entity.*;
import com.moneylens.repository.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.RestTemplate;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.*;

/**
 * AI Copilot — wraps OpenAI Chat Completions API with function-calling (tools).
 *
 * Context strategy (dynamic, based on contextMonths param):
 *
 *   ALWAYS included:
 *     - UserAssessment  (declared income, goal, occupation)
 *     - OverallProfile  (trend, avg scores, confirmed recurring — big picture)
 *     - Budget          (current budget, if one exists)
 *     - Latest month    (most recent StatementProfile — current state)
 *
 *   ONLY when contextMonths specified:
 *     - Specific month profile(s) requested by the frontend
 *
 *   TOOLS (function calling):
 *     - generate_budget        — create/refresh the user's monthly budget
 *     - get_budget_status      — current budget + today's spend pacing
 *     - adjust_budget_category — change a single category's budget amount
 *
 * Token budget per request: ~2,000–3,000 tokens (well within gpt-4o's 128k)
 */
@Service
public class AiCopilotService {

    private static final Logger log = LoggerFactory.getLogger(AiCopilotService.class);
    private static final String OPENAI_URL      = "https://api.openai.com/v1/chat/completions";
    private static final int    MAX_TOOL_ROUNDS = 4;
    private static final int    CONNECT_TIMEOUT = 5_000;   // 5 s
    private static final int    READ_TIMEOUT    = 60_000;  // 60 s — OpenAI p99 is ~30 s

    // Tool schema never changes at runtime — build once, reuse forever
    private volatile List<Map<String, Object>> cachedToolDefs;

    @Value("${openai.api.key}")
    private String apiKey;

    @Value("${openai.model:gpt-4o}")
    private String model;

    @Value("${openai.max-tokens:1024}")
    private int maxTokens;

    private final UserRepository             userRepository;
    private final OverallProfileRepository   overallProfileRepository;
    private final StatementProfileRepository statementProfileRepository;
    private final UserAssessmentRepository   assessmentRepository;
    private final BudgetRepository           budgetRepository;
    private final BudgetService              budgetService;
    private final GoalService                goalService;
    private final ObjectMapper               objectMapper;
    private final RestTemplate               restTemplate;

    public AiCopilotService(
            UserRepository userRepository,
            OverallProfileRepository overallProfileRepository,
            StatementProfileRepository statementProfileRepository,
            UserAssessmentRepository assessmentRepository,
            BudgetRepository budgetRepository,
            BudgetService budgetService,
            GoalService goalService,
            ObjectMapper objectMapper) {

        this.userRepository             = userRepository;
        this.overallProfileRepository   = overallProfileRepository;
        this.statementProfileRepository = statementProfileRepository;
        this.assessmentRepository       = assessmentRepository;
        this.budgetRepository           = budgetRepository;
        this.budgetService              = budgetService;
        this.goalService                = goalService;
        this.objectMapper = objectMapper;

        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(CONNECT_TIMEOUT);
        factory.setReadTimeout(READ_TIMEOUT);
        this.restTemplate = new RestTemplate(factory);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Public API
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * @param phoneNumber    identifies the user
     * @param userMessage    the user's latest message
     * @param history        prior conversation turns (last 10 used)
     * @param contextMonths  optional list of "YYYY-MM" strings requesting
     *                       specific month profiles to be included in context
     */
    public CopilotMessageDto chat(String phoneNumber,
                                  String userMessage,
                                  List<Map<String, String>> history,
                                  List<String> contextMonths) {

        User user = userRepository.findByPhoneNumber(phoneNumber)
                .orElseThrow(() -> new RuntimeException("User not found"));

        // ── Load context from DB ──────────────────────────────────────────
        UserAssessment assessment = assessmentRepository
                .findByUserId(user.getId()).orElse(null);

        OverallProfile overallEntity = overallProfileRepository
                .findByUserId(user.getId()).orElse(null);

        StatementProfile latestEntity = statementProfileRepository
                .findLatestProfiles(user.getId(), 1)
                .stream().findFirst().orElse(null);

        List<StatementProfile> specificProfiles = loadSpecificMonths(
                user.getId(), contextMonths, latestEntity);

        Budget budgetEntity = budgetRepository.findByUserId(user.getId()).orElse(null);

        // ── Deserialize JSONs ─────────────────────────────────────────────
        OverallProfileJson overallJson = deserialize(
                overallEntity != null ? overallEntity.getProfileJson() : null,
                OverallProfileJson.class);

        MonthlyProfileJson latestJson = deserialize(
                latestEntity != null ? latestEntity.getProfileJson() : null,
                MonthlyProfileJson.class);

        List<MonthlyProfileJson> specificJsons = new ArrayList<>();
        for (StatementProfile sp : specificProfiles) {
            if (latestEntity != null && sp.getId().equals(latestEntity.getId())) continue;
            MonthlyProfileJson mj = deserialize(sp.getProfileJson(), MonthlyProfileJson.class);
            if (mj != null) specificJsons.add(mj);
        }

        BudgetJson budgetJson = budgetEntity != null ? toBudgetJson(budgetEntity) : null;

        // ── Build system prompt ───────────────────────────────────────────
        String systemPrompt = buildSystemPrompt(
                user.getId(), assessment, overallJson, latestJson, specificJsons, budgetJson);

        // ── Assemble messages ─────────────────────────────────────────────
        List<Map<String, Object>> messages = new ArrayList<>();
        messages.add(Map.of("role", "system", "content", systemPrompt));

        if (history != null && !history.isEmpty()) {
            int start = Math.max(0, history.size() - 10);
            for (Map<String, String> turn : history.subList(start, history.size())) {
                messages.add(Map.of(
                        "role", turn.getOrDefault("role", "user"),
                        "content", turn.getOrDefault("content", "")));
            }
        }
        messages.add(Map.of("role", "user", "content", userMessage));

        // ── Call OpenAI with tool-calling loop ────────────────────────────
        try {
            ToolCallResult result = callWithTools(phoneNumber, user, messages, contextMonths);

            return new CopilotMessageDto(
                    result.reply,
                    overallEntity != null ? overallEntity.getLatestHealthScore() : null,
                    overallEntity != null ? overallEntity.getArchetype()         : null,
                    result.goalChanged
            );

        } catch (HttpClientErrorException e) {
            log.error("OpenAI client error: {} {}", e.getStatusCode(), e.getResponseBodyAsString());
            if (e.getStatusCode() == HttpStatus.UNAUTHORIZED)
                throw new RuntimeException("OpenAI API key is invalid.");
            if (e.getStatusCode() == HttpStatus.TOO_MANY_REQUESTS)
                throw new RuntimeException("OpenAI rate limit hit. Please try again shortly.");
            throw new RuntimeException("Copilot request failed: " + e.getMessage());
        } catch (HttpServerErrorException e) {
            log.error("OpenAI server error: {}", e.getStatusCode());
            throw new RuntimeException("OpenAI is temporarily unavailable.");
        } catch (Exception e) {
            log.error("Unexpected copilot error: {}", e.getMessage(), e);
            throw new RuntimeException("AI Copilot is temporarily unavailable.");
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Tool-calling loop
    // ─────────────────────────────────────────────────────────────────────────

    private static class ToolCallResult {
        String reply;
        boolean goalChanged;
    }

    private ToolCallResult callWithTools(String phoneNumber, User user,
                                         List<Map<String, Object>> messages,
                                         List<String> contextMonths) {
        ToolCallResult result = new ToolCallResult();

        for (int round = 0; round < MAX_TOOL_ROUNDS; round++) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("model",       model);
            body.put("max_tokens",  maxTokens);
            body.put("temperature", 0.7);
            body.put("messages",    messages);
            body.put("tools",       getToolDefs());
            body.put("tool_choice", "auto");

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.setBearerAuth(apiKey);

            log.info("Calling OpenAI (round {}) for user={} model={}", round, phoneNumber, model);

            ResponseEntity<Map> response = restTemplate.postForEntity(
                    OPENAI_URL, new HttpEntity<>(body, headers), Map.class);

            Map<String, Object> message = extractMessage(response.getBody());
            if (message == null) {
                result.reply = "I couldn't generate a response. Please try again.";
                return result;
            }

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> toolCalls = (List<Map<String, Object>>) message.get("tool_calls");

            if (toolCalls == null || toolCalls.isEmpty()) {
                Object content = message.get("content");
                result.reply = content != null ? content.toString().trim() : "No response received.";
                log.info("OpenAI replied {} chars for user={} (round {})",
                        result.reply.length(), phoneNumber, round);
                return result;
            }

            // Append assistant message with tool_calls to the conversation
            messages.add(message);

            // Execute each tool call and append tool result messages
            for (Map<String, Object> call : toolCalls) {
                String toolCallId = String.valueOf(call.get("id"));
                @SuppressWarnings("unchecked")
                Map<String, Object> function = (Map<String, Object>) call.get("function");
                String fnName = String.valueOf(function.get("name"));
                String argsJson = String.valueOf(function.get("arguments"));

                log.info("Tool call requested: {} args={} (user={})", fnName, argsJson, phoneNumber);

                String toolResultJson;
                try {
                    toolResultJson = dispatchTool(fnName, argsJson, user, result);
                } catch (Exception e) {
                    log.warn("Tool '{}' failed for user={}: {}", fnName, phoneNumber, e.getMessage());
                    toolResultJson = errorJson(e.getMessage());
                }

                messages.add(Map.of(
                        "role", "tool",
                        "tool_call_id", toolCallId,
                        "content", toolResultJson
                ));
            }
            // loop again — model will see tool results and respond
        }

        result.reply = "I had trouble completing that request after several attempts. Please try rephrasing or try again shortly.";
        return result;
    }

    /**
     * Dispatches a single tool call by name. Returns a JSON string to feed back to the model.
     */
    private String dispatchTool(String fnName, String argsJson, User user, ToolCallResult result) throws Exception {
        Map<String, Object> args = argsJson == null || argsJson.isBlank()
                ? Map.of()
                : objectMapper.readValue(argsJson, Map.class);

        switch (fnName) {
            case "generate_budget": {
                boolean refresh = Boolean.TRUE.equals(args.get("refresh"));
                BudgetJson budget;
                if (refresh) {
                    budget = budgetService.generateBaseline(user.getId(), true);
                } else {
                    budget = budgetService.getOrGenerateBudget(user.getId());
                }
                return objectMapper.writeValueAsString(budget);
            }

            case "get_budget_status": {
                BudgetJson budget = budgetService.getOrGenerateBudget(user.getId());
                BudgetJson.DailyPacing pacing = budgetService.getDailyPacing(user.getId());
                Map<String, Object> combined = new LinkedHashMap<>();
                combined.put("budget", budget);
                combined.put("pacing", pacing);
                return objectMapper.writeValueAsString(combined);
            }
            case "update_income": {
                Object incomeObj = args.get("new_income");
                BigDecimal newIncome = new BigDecimal(String.valueOf(incomeObj));
                BudgetJson.IncomeUpdateResult updateResult = budgetService.updateIncomeAndRebudget(user.getId(), newIncome);
                return objectMapper.writeValueAsString(updateResult);
            }

            case "update_savings_target": {
                String goalName = args.get("goal_name") != null ? String.valueOf(args.get("goal_name")) : null;
                BigDecimal targetAmount = args.get("target_amount") != null
                        ? new BigDecimal(String.valueOf(args.get("target_amount"))) : null;
                LocalDate deadline = null;
                if (args.get("deadline") != null) {
                    try {
                        deadline = LocalDate.parse(String.valueOf(args.get("deadline")));
                    } catch (Exception e) {
                        return errorJson("Invalid date format for deadline — expected YYYY-MM-DD.");
                    }
                }
                BudgetJson.GoalUpdateResult updateResult = budgetService.updateGoalAndRebudget(
                        user.getId(), goalName, targetAmount, deadline);
                return objectMapper.writeValueAsString(updateResult);
            }

            case "adjust_budget_category": {
                String category = String.valueOf(args.get("category"));
                Object amountObj = args.get("new_amount");
                BigDecimal newAmount = new BigDecimal(String.valueOf(amountObj));
                String reason = args.get("reason") != null ? String.valueOf(args.get("reason")) : null;

                BudgetJson updated = budgetService.adjustCategory(user.getId(), category, newAmount, reason);
                return objectMapper.writeValueAsString(updated);
            }

            case "create_financial_goal": {
                String title      = String.valueOf(args.get("title"));
                double amount     = Double.parseDouble(String.valueOf(args.get("target_amount")));
                String emoji      = args.get("emoji") != null ? String.valueOf(args.get("emoji")) : null;
                String targetDate = args.get("target_date") != null ? String.valueOf(args.get("target_date")) : null;
                String frequency  = args.get("frequency") != null ? String.valueOf(args.get("frequency")) : "MONTHLY";
                LocalDate date    = null;
                if (targetDate != null && !targetDate.equals("null")) {
                    try { date = LocalDate.parse(targetDate); } catch (Exception ignored) {}
                }
                com.moneylens.entity.FinancialGoal created = goalService.create(
                        user, title, emoji, java.math.BigDecimal.valueOf(amount), date, frequency);
                result.goalChanged = true;
                Map<String, Object> goalMap = new LinkedHashMap<>();
                goalMap.put("id",          created.getId());
                goalMap.put("title",       created.getTitle());
                goalMap.put("emoji",       created.getEmoji());
                goalMap.put("targetAmount", created.getTargetAmount());
                goalMap.put("frequency",   created.getFrequency());
                goalMap.put("status",      "created");
                if (created.getAiPlan() != null) {
                    try { goalMap.put("plan", objectMapper.readValue(created.getAiPlan(), Map.class)); }
                    catch (Exception ignored) {}
                }
                return objectMapper.writeValueAsString(goalMap);
            }

            case "list_financial_goals": {
                String summary = goalService.buildGoalsSummary(user.getId());
                return objectMapper.writeValueAsString(Map.of("goals", summary));
            }

            case "update_goal_progress": {
                long goalId = Long.parseLong(String.valueOf(args.get("goal_id")));
                double saved = Double.parseDouble(String.valueOf(args.get("saved_amount")));
                return goalService.findByIdAndUser(goalId, user.getId())
                        .map(goal -> {
                            com.moneylens.entity.FinancialGoal updated = goalService.updateProgress(
                                    goal, java.math.BigDecimal.valueOf(saved));
                            result.goalChanged = true;
                            try { return objectMapper.writeValueAsString(Map.of(
                                    "id", updated.getId(),
                                    "savedAmount", updated.getSavedAmount(),
                                    "status", updated.getStatus())); }
                            catch (Exception e) { return errorJson("Serialization error"); }
                        })
                        .orElse(errorJson("Goal not found"));
            }

            default:
                return errorJson("Unknown tool: " + fnName);
        }
    }

    private String errorJson(String message) {
        try {
            return objectMapper.writeValueAsString(Map.of("error", message != null ? message : "Unknown error"));
        } catch (Exception e) {
            return "{\"error\":\"Unknown error\"}";
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Tool definitions (OpenAI function-calling schema)
    // ─────────────────────────────────────────────────────────────────────────

    private List<Map<String, Object>> getToolDefs() {
        if (cachedToolDefs == null) cachedToolDefs = buildToolDefinitions();
        return cachedToolDefs;
    }

    private List<Map<String, Object>> buildToolDefinitions() {
        List<Map<String, Object>> tools = new ArrayList<>();

        // generate_budget
        tools.add(toolDef(
                "generate_budget",
                "Create the user's monthly budget if they don't have one, or refresh it based on their latest spending data. " +
                        "Use this when the user asks for a budget, asks to create/set up a budget, or asks to recalculate/refresh their budget. " +
                        "Do NOT use this just to view an existing budget — use get_budget_status for that.",
                Map.of(
                        "type", "object",
                        "properties", Map.of(
                                "refresh", Map.of(
                                        "type", "boolean",
                                        "description", "Set true to force-recalculate the budget from latest spending data, even if one already exists. Set false (default) to just return the existing budget or create one if none exists."
                                )
                        ),
                        "required", List.of()
                )
        ));

        // get_budget_status
        tools.add(toolDef(
                "get_budget_status",
                "Get the user's current budget (total + per-category amounts) and today's spending pace " +
                        "(how much they've spent this month, daily allowance for remaining days, and whether they're ahead/behind). " +
                        "Use this whenever the user asks about their budget, how much they can spend, daily spending limits, " +
                        "or how they're tracking against their budget.",
                Map.of(
                        "type", "object",
                        "properties", Map.of(),
                        "required", List.of()
                )
        ));

        // update_income
        tools.add(toolDef(
                "update_income",
                "Update the user's current monthly income and immediately regenerate their budget using the new figure. " +
                        "Use this when the user says their income/salary has changed (increased, decreased, new job, raise, etc.). " +
                        "After calling this, clearly state the old and new income, and summarize how the budget changed as a result.",
                Map.of(
                        "type", "object",
                        "properties", Map.of(
                                "new_income", Map.of(
                                        "type", "number",
                                        "description", "The user's new monthly income in rupees (whole number)."
                                )
                        ),
                        "required", List.of("new_income")
                )
        ));

// update_savings_target
        tools.add(toolDef(
                "update_savings_target",
                "Update the budget planner's savings target — its name, target amount, and/or deadline — and regenerate " +
                        "the monthly budget allocation to reflect the new target. Use this ONLY for the top-level savings " +
                        "objective stored in the budget (e.g. 'I want to save ₹500000 by next year' or " +
                        "'push my savings deadline to 2029'). Do NOT use this when creating or editing a specific " +
                        "trackable goal (iPhone, trip, emergency fund) — use create_financial_goal for those instead. " +
                        "Only include fields the user actually specified — leave others null to keep current values. " +
                        "After calling this, state what changed and the new monthly savings target.",
                Map.of(
                        "type", "object",
                        "properties", Map.of(
                                "goal_name", Map.of(
                                        "type", "string",
                                        "description", "Name/description of the goal, e.g. 'Buy a car', 'Emergency fund'. Omit if unchanged."
                                ),
                                "target_amount", Map.of(
                                        "type", "number",
                                        "description", "Target amount in rupees. Omit if unchanged."
                                ),
                                "deadline", Map.of(
                                        "type", "string",
                                        "description", "Target date in YYYY-MM-DD format. Omit if unchanged."
                                )
                        ),
                        "required", List.of()
                )
        ));

        // adjust_budget_category
        tools.add(toolDef(
                "adjust_budget_category",
                "Change the budgeted amount for a single spending category (e.g. food, travel, shopping). " +
                        "Use this when the user explicitly asks to increase, decrease, or set a specific category's budget " +
                        "(e.g. 'increase my travel budget to 8000' or 'I need more for groceries this month'). " +
                        "Before calling this, check get_budget_status first if you haven't already, so you can sanity-check " +
                        "the request against their income and other categories, and mention any tradeoff in your reply.",
                Map.of(
                        "type", "object",
                        "properties", Map.of(
                                "category", Map.of(
                                        "type", "string",
                                        "description", "The spending category to adjust, matching one of the category names from the budget (e.g. 'food & dining', 'transport', 'shopping', 'entertainment'). Use lowercase."
                                ),
                                "new_amount", Map.of(
                                        "type", "number",
                                        "description", "The new monthly budget amount for this category, in rupees (whole number)."
                                ),
                                "reason", Map.of(
                                        "type", "string",
                                        "description", "Short reason for the change, to show the user later (e.g. 'Increased for upcoming trip'). Optional."
                                )
                        ),
                        "required", List.of("category", "new_amount")
                )
        ));

        // create_financial_goal
        tools.add(toolDef(
                "create_financial_goal",
                "Create a new financial goal for the user — e.g. 'Buy iPhone 16', 'Goa trip', 'Emergency fund'. " +
                        "Use this when the user asks to save for something, asks if they can afford something, or asks you to create a goal. " +
                        "After calling this, summarise the goal, the monthly/weekly saving required, and the estimated timeline. " +
                        "Be encouraging and specific — mention their actual saving capacity from the financial data.",
                Map.of(
                        "type", "object",
                        "properties", Map.of(
                                "title",         Map.of("type","string",  "description","Short goal name, e.g. 'iPhone 16 Pro', 'Goa Trip', 'Emergency Fund'"),
                                "target_amount", Map.of("type","number",  "description","Goal amount in rupees (whole number)"),
                                "emoji",         Map.of("type","string",  "description","A single emoji representing the goal, e.g. 📱 ✈️ 🏠"),
                                "target_date",   Map.of("type","string",  "description","Optional target date in YYYY-MM-DD format. Omit if user didn't specify."),
                                "frequency",     Map.of("type","string",  "enum",List.of("MONTHLY","WEEKLY"), "description","Saving frequency. Default MONTHLY.")
                        ),
                        "required", List.of("title","target_amount")
                )
        ));

        // list_financial_goals
        tools.add(toolDef(
                "list_financial_goals",
                "Fetch the user's current financial goals with progress. The [FINANCIAL GOALS] section in the system " +
                        "prompt is already up-to-date — only call this tool if that section is explicitly missing or the " +
                        "user specifically asks to refresh/reload their goals.",
                Map.of("type","object","properties",Map.of(),"required",List.of())
        ));

        // update_goal_progress
        tools.add(toolDef(
                "update_goal_progress",
                "Update how much the user has saved towards a specific goal. " +
                        "Use this when user says 'I saved X towards my goal' or 'I added X to my iPhone fund'.",
                Map.of(
                        "type", "object",
                        "properties", Map.of(
                                "goal_id",     Map.of("type","number", "description","The numeric ID of the goal to update"),
                                "saved_amount",Map.of("type","number", "description","Total amount saved so far in rupees")
                        ),
                        "required", List.of("goal_id","saved_amount")
                )
        ));

        return tools;
    }

    private Map<String, Object> toolDef(String name, String description, Map<String, Object> parameters) {
        Map<String, Object> function = new LinkedHashMap<>();
        function.put("name", name);
        function.put("description", description);
        function.put("parameters", parameters);

        Map<String, Object> tool = new LinkedHashMap<>();
        tool.put("type", "function");
        tool.put("function", function);
        return tool;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // System prompt builder
    // ─────────────────────────────────────────────────────────────────────────

    private String buildSystemPrompt(Long userId,
                                     UserAssessment assessment,
                                     OverallProfileJson overall,
                                     MonthlyProfileJson latest,
                                     List<MonthlyProfileJson> specific,
                                     BudgetJson budget) {
        StringBuilder sb = new StringBuilder();

        sb.append("""
                You are MoneyLens Copilot — a sharp, empathetic personal finance advisor for Indian users.

                VOICE & STYLE:
                - Direct, confident, specific — ground every claim in the data below
                - Use ₹ and Indian number conventions (lakh/crore where the number warrants it)
                - Default to short conversational paragraphs (2-4 sentences). Use bullet points
                  only when listing 3+ distinct items (e.g. comparing months, listing categories)
                - Never use markdown headers (##, ###) — this is a chat interface, not a document
                - Never invent numbers not present in the data sections below or returned by tools
                - If asked about a month not included below, say you don't have that month loaded
                  and suggest they open that month's screen to discuss it

                DATA SECTIONS (only included when relevant/available):
                - [USER PROFILE] — declared income, goal, occupation
                - [OVERALL PICTURE] — trend, avg scores, confirmed recurring across all months
                - [BUDGET] — user's current monthly budget by category, if one exists
                - [LATEST MONTH] — most recent month's actuals
                - [MONTH DETAIL] — specific month(s) the user is currently viewing
                - [FINANCIAL GOALS] — user's current savings goals and progress

                FINANCIAL GUARDRAILS:
                - You are not a SEBI-registered advisor. For specific investment products
                  (mutual funds, stocks, insurance), give general guidance and add a brief
                  "consult a registered advisor" note — don't make it the focus of every reply
                - Never encourage leverage, crypto speculation, or informal/peer lending
                - For tax questions, give general direction and recommend a CA for filing specifics
                - If the user seems stressed about money, acknowledge that first, then get practical
                - Prefer statement-derived (actual) figures over declared figures when they conflict,
                  and say so briefly if it's relevant to the answer
                
                TOOLS:
                - generate_budget — create or refresh the user's monthly budget
                - get_budget_status — current budget + today's spend pacing (use this for
                  "how much can I spend" / "how am I doing on my budget" type questions,
                  even if [BUDGET] is already shown above, since it includes live pacing)
                - adjust_budget_category — change one category's budget amount on request
                - update_income — update declared monthly income and rebudget (use when
                  income/salary changes are mentioned)
                - update_savings_target — update the budget-level savings objective name/amount/deadline and rebudget
                  (NOT for specific trackable goals like trips or gadgets — use create_financial_goal for those)
                - create_financial_goal — create a savings goal with an AI-generated plan
                  (use when user asks "can I afford X", "I want to save for Y", or asks
                  you to create a goal. Ask for target amount and optional deadline first.)
                - list_financial_goals — reload goals data (skip if [FINANCIAL GOALS] is already in context)
                - update_goal_progress — update how much the user has saved toward a goal

                GOAL CREATION BEHAVIOUR:
                - When a user asks "can I afford X" or "I want to buy X", detect the intent,
                  confirm the target amount, and suggest creating a goal if it's a significant
                  purchase. Say: "Want me to set up a savings goal for this?"
                - After creating a goal, summarise the plan in 2-3 sentences: per-period amount,
                  estimated timeline, and one key tip.
                - When the user says "create a goal for me" or similar, call create_financial_goal
                  directly after confirming title and amount.

                AFTER ANY TOOL THAT CHANGES THE BUDGET (adjust_budget_category, update_income,
                update_savings_target, generate_budget with refresh):
                - State the old value and new value clearly (e.g. "Your income: ₹55,000 → ₹70,000")
                - Summarize what changed in the budget as a result (savings target, affected categories)
                - Explicitly note what did NOT change — your financial health score and past
                  insights are based on your statement history and won't change until your
                  next statement upload reflects this
                - Keep this to 2-3 sentences — don't over-explain

                """);

        // Assessment
        if (assessment != null) {
            sb.append("[USER PROFILE]\n");
            sb.append(String.format("Name: %s | Occupation: %s%n",
                    nvl(assessment.getFullName()), nvl(assessment.getOccupation())));
            sb.append(String.format("Declared income: ₹%s/month | Declared savings: ₹%s/month%n",
                    fmt(assessment.getMonthlyIncome()), fmt(assessment.getMonthlySavings())));
            sb.append(String.format("Has debt: %s | Emergency fund: %s (%s months)%n",
                    nvl(assessment.getHasDebt()), nvl(assessment.getHasEmergencyFund()),
                    nvl(assessment.getEmergencyMonths())));
            if (assessment.getFinancialGoal() != null) {
                sb.append(String.format("Goal: %s | Target: ₹%s by %s%n",
                        assessment.getFinancialGoal(),
                        fmt(assessment.getGoalAmount()), nvl(assessment.getGoalDeadline())));
            }
            sb.append("\n");
        }

        // Overall profile
        if (overall != null) {
            sb.append(String.format("[OVERALL PICTURE — %s, %d months]\n",
                    overall.getPeriod(), overall.getMonthsAnalyzed()));
            sb.append(String.format("Archetype: %s | Avg score: %d/100 | Latest: %d/100%n",
                    overall.getArchetype(), overall.getAvgHealthScore(), overall.getLatestHealthScore()));
            sb.append(String.format("Trend: %s (%+d pts over last 3 months) | Best: %s (%d) | Worst: %s (%d)%n",
                    overall.getTrend(), overall.getTrendDelta(),
                    overall.getBestMonth(), overall.getBestHealthScore(),
                    overall.getWorstMonth(), overall.getWorstHealthScore()));

            if (overall.getAvgFinancials() != null) {
                OverallProfileJson.AvgFinancials f = overall.getAvgFinancials();
                sb.append(String.format("Avg income: ₹%s/mo | Avg spend: ₹%s/mo | Avg savings: ₹%s/mo | Lifestyle: %.0f%%%n",
                        fmt(f.avgIncome), fmt(f.avgSpend), fmt(f.avgSavings), f.avgLifestyleRatio));
                sb.append(String.format("Income consistency: %.0f%% of months had salary detected%n",
                        f.incomeConsistency * 100));
            }

            if (overall.getAvgSubScores() != null) {
                OverallProfileJson.AvgSubScores s = overall.getAvgSubScores();
                sb.append(String.format("Avg sub-scores — Savings:%d Discipline:%d Debt:%d Income:%d Emergency:%d Goal:%d%n",
                        s.savingsRate, s.spendingDiscipline, s.debtBurden,
                        s.incomeStability, s.emergencyCushion, s.goalAlignment));
            }

            if (overall.getConfirmedRecurring() != null && !overall.getConfirmedRecurring().isEmpty()) {
                sb.append("Confirmed recurring: ");
                overall.getConfirmedRecurring().forEach(r ->
                        sb.append(String.format("%s ₹%s/mo (%dmo)%s, ",
                                r.merchant, fmt(r.amount), r.monthsDetected,
                                r.declaredInAssessment ? "" : "⚠undeclared")));
                sb.append(String.format("— total ₹%s/mo%n", fmt(overall.getConfirmedRecurringTotal())));
            }

            if (overall.getBiggestImprovements() != null && !overall.getBiggestImprovements().isEmpty())
                sb.append("Improving: ").append(String.join(", ", overall.getBiggestImprovements())).append("\n");
            if (overall.getBiggestWeaknesses() != null && !overall.getBiggestWeaknesses().isEmpty())
                sb.append("Persistent weak spots: ").append(String.join(", ", overall.getBiggestWeaknesses())).append("\n");
            if (overall.getOverallStrengths() != null)
                sb.append("Strengths: ").append(String.join("; ", overall.getOverallStrengths())).append("\n");
            if (overall.getOverallRisks() != null)
                sb.append("Risks: ").append(String.join("; ", overall.getOverallRisks())).append("\n");
            if (overall.getOverallActions() != null)
                sb.append("Top actions: ").append(String.join("; ", overall.getOverallActions())).append("\n");

            if (overall.getMonthlyScores() != null && !overall.getMonthlyScores().isEmpty()) {
                sb.append("Score history: ");
                overall.getMonthlyScores().forEach(ms ->
                        sb.append(String.format("%s=%d ", ms.month, ms.score)));
                sb.append("\n");
            }
            sb.append("\n");
        }

        // Budget
        if (budget != null) {
            sb.append("[BUDGET]\n");
            sb.append(String.format("Total monthly budget: ₹%s | Savings target: ₹%s%n",
                    fmt(budget.totalBudget), fmt(budget.savingsTarget)));
            if (budget.categoryBudgets != null && !budget.categoryBudgets.isEmpty()) {
                sb.append("By category: ");
                budget.categoryBudgets.forEach((cat, amt) ->
                        sb.append(String.format("%s:₹%s ", cat, fmt(amt))));
                sb.append("\n");
            }
            sb.append(String.format("Source: %s (generated %s)%n", budget.source, budget.generatedAt));
            sb.append("\n");
        } else {
            sb.append("[BUDGET]\nNo budget set up yet. If the user asks about budgeting or spending limits, " +
                    "offer to generate one using generate_budget.\n\n");
        }

        // Latest month
        if (latest != null) {
            appendMonthSection(sb, latest, "[LATEST MONTH — " + latest.getMonth() + "]");
        }

        // Specific months
        for (MonthlyProfileJson mj : specific) {
            appendMonthSection(sb, mj, "[MONTH DETAIL — " + mj.getMonth() + "]");
        }


        // Financial Goals
        String goalsSummary = goalService.buildGoalsSummary(userId);
        sb.append("[FINANCIAL GOALS]\n");
        sb.append(goalsSummary).append("\n\n");

        if (overall == null && latest == null && assessment == null) {
            sb.append("""
                    NOTE: This user has not yet uploaded statements or completed their assessment.
                    Gently encourage onboarding (assessment + first statement upload) when relevant,
                    but still answer general Indian personal finance questions in the meantime.
                    """);
        }

        return sb.toString();
    }

    private void appendMonthSection(StringBuilder sb, MonthlyProfileJson m, String header) {
        sb.append(header).append("\n");
        sb.append(String.format("Score: %d/100  Archetype: %s  TX: %d%n",
                m.getHealthScore(), m.getArchetype(), m.getTransactionCount()));

        if (m.getFinancials() != null) {
            MonthlyProfileJson.Financials f = m.getFinancials();
            sb.append(String.format("Credits:₹%s  Debits:₹%s  Net:₹%s  Lifestyle:%.1f%%%n",
                    fmt(f.totalCredits), fmt(f.totalDebits), fmt(f.netStatementFlow), f.lifestyleRatio));
        }

        if (m.getSalaryRunway() != null && m.getSalaryRunway().insight != null)
            sb.append("Runway: ").append(m.getSalaryRunway().insight).append("\n");

        if (m.getWeeklyPattern() != null)
            sb.append(String.format("Weekly: %s — %s%n",
                    m.getWeeklyPattern().pattern, m.getWeeklyPattern().insight));

        if (m.getSpendingBreakdown() != null && !m.getSpendingBreakdown().isEmpty()) {
            sb.append("Spending: ");
            m.getSpendingBreakdown().entrySet().stream().limit(5)
                    .forEach(e -> sb.append(String.format("%s:₹%s ", e.getKey(), fmt(e.getValue()))));
            sb.append("\n");
        }

        if (m.getTopMerchants() != null && !m.getTopMerchants().isEmpty()) {
            sb.append("Top merchants: ");
            m.getTopMerchants().forEach(t -> sb.append(String.format("%s:₹%s ", t.name, fmt(t.amount))));
            sb.append("\n");
        }


        if (m.getRecurringPayments() != null && !m.getRecurringPayments().isEmpty()) {
            sb.append("Recurring: ");
            m.getRecurringPayments().forEach(r -> sb.append(String.format(
                    "%s:₹%s[%s] ", r.merchant, fmt(r.amount), r.type)));
            sb.append("\n");
        }

        if (m.getStrengths() != null)
            sb.append("Strengths: ").append(String.join("; ", m.getStrengths())).append("\n");
        if (m.getRisks() != null)
            sb.append("Risks: ").append(String.join("; ", m.getRisks())).append("\n");
        if (m.getRecommendedActions() != null)
            sb.append("Actions: ").append(String.join("; ", m.getRecommendedActions())).append("\n");

        sb.append("\n");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────────────────

    private List<StatementProfile> loadSpecificMonths(Long userId,
                                                      List<String> contextMonths,
                                                      StatementProfile latestEntity) {
        if (contextMonths == null || contextMonths.isEmpty()) return Collections.emptyList();

        List<StatementProfile> result = new ArrayList<>();
        for (String ym : contextMonths) {
            try {
                String[] parts = ym.split("-");
                int year  = Integer.parseInt(parts[0]);
                int month = Integer.parseInt(parts[1]);
                statementProfileRepository
                        .findByUserIdAndProfileYearAndProfileMonth(userId, year, month)
                        .ifPresent(result::add);
            } catch (Exception e) {
                log.warn("Invalid contextMonth format '{}': {}", ym, e.getMessage());
            }
        }
        return result;
    }

    private BudgetJson toBudgetJson(Budget budget) {
        BudgetJson json = new BudgetJson();
        json.totalBudget = budget.getTotalBudget();
        json.savingsTarget = budget.getSavingsTarget();
        json.categoryBudgets = parseMap(budget.getCategoryBudgetsJson(), BigDecimal.class);
        json.reasoning = parseMap(budget.getReasoningJson(), String.class);
        json.source = budget.getSource() != null ? budget.getSource().name() : "AUTO";
        json.generatedAt = budget.getGeneratedAt() != null ? budget.getGeneratedAt().toLocalDate().toString() : null;
        return json;
    }

    private <V> Map<String, V> parseMap(String jsonStr, Class<V> valueType) {
        if (jsonStr == null || jsonStr.isBlank()) return new LinkedHashMap<>();
        try {
            return objectMapper.readValue(jsonStr, objectMapper.getTypeFactory()
                    .constructMapType(LinkedHashMap.class, String.class, valueType));
        } catch (Exception e) {
            return new LinkedHashMap<>();
        }
    }

    private <T> T deserialize(String json, Class<T> cls) {
        if (json == null || json.isBlank()) return null;
        try { return objectMapper.readValue(json, cls); }
        catch (Exception e) { log.warn("Failed to deserialize {}: {}", cls.getSimpleName(), e.getMessage()); return null; }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> extractMessage(Map<?, ?> body) {
        if (body == null) return null;
        List<Map<String, Object>> choices = (List<Map<String, Object>>) body.get("choices");
        if (choices == null || choices.isEmpty()) return null;
        return (Map<String, Object>) choices.get(0).get("message");
    }

    private String fmt(BigDecimal v) {
        if (v == null) return "N/A";
        return v.setScale(0, RoundingMode.HALF_UP).toPlainString();
    }

    private String nvl(Object v) { return v != null ? v.toString() : "N/A"; }
}
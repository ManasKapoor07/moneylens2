package com.moneylens.dto;

/**
 * Response payload for POST /api/copilot/chat.
 *
 * Carries the AI reply plus a lightweight profile snapshot so the frontend
 * can refresh score badges and archetype labels without a separate API call.
 *
 * Fields:
 *   reply        → the AI-generated response text (always present)
 *   healthScore  → current composite health score 0–100 (null if profile not built yet)
 *   archetype    → user's financial archetype label (null if profile not built yet)
 */
public class CopilotMessageDto {

    private final String  reply;
    private final Integer healthScore;
    private final String  archetype;
    private final boolean goalChanged;

    public CopilotMessageDto(String reply, Integer healthScore, String archetype, boolean goalChanged) {
        this.reply       = reply;
        this.healthScore = healthScore;
        this.archetype   = archetype;
        this.goalChanged = goalChanged;
    }

    public String  getReply()       { return reply; }
    public Integer getHealthScore() { return healthScore; }
    public String  getArchetype()   { return archetype; }
    public boolean isGoalChanged()  { return goalChanged; }
}
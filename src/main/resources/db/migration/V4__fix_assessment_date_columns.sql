ALTER TABLE user_assessments
ALTER COLUMN goal_deadline TYPE DATE USING goal_deadline::DATE;
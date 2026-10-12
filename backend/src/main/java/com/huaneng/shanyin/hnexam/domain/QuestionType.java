package com.huaneng.shanyin.hnexam.domain;

public enum QuestionType {
    SINGLE_CHOICE,
    MULTIPLE_CHOICE,
    TRUE_FALSE,
    FILL_BLANK,
    CALCULATION,
    SHORT_ANSWER,
    IMAGE_CASE,
    ESSAY;

    public boolean isAutomaticallyGraded() {
        return this == SINGLE_CHOICE || this == MULTIPLE_CHOICE || this == TRUE_FALSE || this == FILL_BLANK;
    }
}


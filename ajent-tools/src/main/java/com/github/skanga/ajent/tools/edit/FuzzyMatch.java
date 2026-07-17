package com.github.skanga.ajent.tools.edit;

/** Result of locating a possibly drifted edit region. Positions use Java string indices. */
public record FuzzyMatch(
    boolean ok, int position, int length, int count, String adjustedNewText, int strategy) {}

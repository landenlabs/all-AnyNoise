package com.landenlabs.allAnyNoise.model;

/** Coarse DSP-derived sound tag for a detected noise episode. */
public enum PhysicalSoundType {
    /** Short transient, high zero-crossing rate, rapid decay (e.g. a click or tap). */
    QUICK_CLICK,
    /** Tonal, narrow/stable frequency spectrum, steady amplitude (e.g. a fan or hum). */
    STEADY_HUM,
    /** High peak energy with a sharp attack and broad-spectrum noise (e.g. a bang or slam). */
    LOUD_BANG,
    /** Energy concentrated below ~150 Hz (e.g. a rumble or engine idle). */
    LOW_RUMBLE,
    UNKNOWN
}

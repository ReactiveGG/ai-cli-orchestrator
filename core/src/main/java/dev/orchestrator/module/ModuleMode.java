package dev.orchestrator.module;

/** How a named module is instantiated. */
public enum ModuleMode {
    /** Use the CLI when it is on PATH, otherwise fall back to the stub. */
    AUTO,
    /** Always shell out; execution fails if the CLI is missing. */
    CLI,
    /** Never shell out; echo prompts. */
    STUB
}

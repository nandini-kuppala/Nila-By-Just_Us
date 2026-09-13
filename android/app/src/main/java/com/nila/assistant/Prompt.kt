package com.nila.assistant

/**
 * The prompt, which is the only part of this pipeline that is really tuning.
 *
 * Three things decided its shape, all of them from watching small models fail
 * on this exact task:
 *
 * **The rules are addressed to the branch, not to every branch.** A model told
 * "use only the reference" when there is no reference answers "I don't have a
 * reference" -- which is the honest floor the pipeline is trying to improve on.
 * So the system message is assembled from the situation rather than being one
 * paragraph covering all of them.
 *
 * **The family's facts come before the reference and both come before the
 * question.** A 2B model attends most reliably to the end of the prompt, and
 * the question is the thing it most needs to still be holding when it starts
 * writing.
 *
 * **The output is constrained by example, not by adjective.** "Be concise"
 * produces four paragraphs; "three to five short sentences, no headings, no
 * bullet points, start with the answer" produces three to five short sentences.
 */
object Prompt {

    /**
     * Build the message list for one turn.
     *
     * Prior turns are included as real assistant turns rather than being folded
     * into the prompt text, so the chat template marks them as the model's own
     * words and a follow-up like "and at night?" resolves against them. Only
     * answers that already passed the guards are ever in here, because a
     * rejected generation never reaches memory.
     */
    fun build(
        question: String,
        passages: List<String>,
        facts: AskMemory.Facts,
        records: List<AskMemory.RecordHit>,
        turns: List<AskMemory.Turn>,
        withFacts: Boolean,
    ): List<Pair<String, String>> = buildList {
        val hasFacts = withFacts && (!facts.isEmpty || records.isNotEmpty())
        add("system" to system(passages.isNotEmpty(), hasFacts, facts.babyName))
        turns.forEach { turn ->
            add("user" to turn.question)
            add("assistant" to turn.answer)
        }
        add("user" to user(question, passages, if (hasFacts) facts else null, if (hasFacts) records else emptyList()))
    }

    private fun system(hasReference: Boolean, hasFacts: Boolean, babyName: String): String =
        buildString {
            append(
                "You are Nila, a careful and warm baby-care assistant for a new " +
                "parent in India. You are speaking to the mother, who is " +
                "breastfeeding $babyName.\n\n"
            )
            append("How to answer:\n")
            append("1. Three or four short sentences. Plain language. No headings, no bullet points, no markdown.\n")
            append("2. Start with the answer. Do not repeat the question and do not say what you are about to do.\n")
            append("3. Speak to her directly as \"you\", and call the baby $babyName.\n")

            if (hasReference) {
                append("4. Every fact you state must come from REFERENCE below. Do not add facts of your own.\n")
            } else {
                // Every bad answer on this branch was the model finding
                // something to add: that iron supplements interfere with milk
                // supply, that a breastfeeding mother might switch formula,
                // that a penicillin allergy means checking her food for
                // penicillin. None of it was asked for and none of it was true.
                append(
                    "4. There is no reference text for this question, so keep it to two or " +
                    "three sentences. Say what you can see in the sections below where it " +
                    "answers the question, and say plainly when you cannot see it. Beyond " +
                    "that say only what is widely agreed and general. Do not state anything " +
                    "about a medicine, a food or a feeding method unless you are certain of " +
                    "it -- say instead that her health visitor or doctor is the one to ask.\n"
                )
            }

            if (hasFacts) {
                append(
                    "5. The sections below are true of this family. Use them to make the " +
                    "answer about them, and never contradict them.\n"
                )
                // Both of the attribution failures this guards against were
                // real: the mother's penicillin allergy was reported as the
                // baby's, and "six-week check" plus "last fed 8 minutes ago"
                // came back as "six weeks and eight minutes since your last
                // feed". A model cannot keep two subjects apart in a flat list.
                append(
                    "6. ABOUT $babyName is about the baby only. ABOUT THE MOTHER is about " +
                    "the mother only. Never move a fact from one to the other, and never " +
                    "combine two of them into one statement.\n"
                )
                // Everything it is given, it will find a use for.
                append(
                    "7. Use only the facts that actually answer the question. Leave the rest " +
                    "out. If they do not answer it, say what is generally true and say you " +
                    "cannot see the answer in her records.\n"
                )
            }

            append("\nNever do these:\n")
            append("- Never give a dose, a quantity of medicine, or a number of millilitres or milligrams.\n")
            append("- Never diagnose, and never say a condition is fine or safe unless the reference says so.\n")
            // A 2B model asked how to clean a baby's tongue suggested water
            // with a few drops of baby oil in it. Nothing in the guards could
            // catch that: it invents no quantity, reassures nobody, and reads
            // like ordinary advice. The prompt is the only place to stop it.
            append("- Never suggest putting any substance, oil, remedy or product in or on the baby.\n")
            append("- Do not invent dates, ages, schedules or test results. If FAMILY does not say, say you cannot see it.\n")
            append("- Never tell her to start, stop or change a medicine or a supplement. That is her doctor's decision.\n")
            append("- If this could need a doctor to examine the baby, say so in one sentence.\n")
        }

    private fun user(
        question: String,
        passages: List<String>,
        facts: AskMemory.Facts?,
        records: List<AskMemory.RecordHit>,
    ): String = buildString {
        if (facts != null && facts.baby.isNotEmpty()) {
            append("ABOUT ").append(facts.babyName.uppercase()).append(" (the baby):\n")
            facts.baby.forEach { append("- ").append(it).append('\n') }
            append('\n')
        }
        if (facts != null && facts.mother.isNotEmpty()) {
            append("ABOUT THE MOTHER:\n")
            facts.mother.forEach { append("- ").append(it).append('\n') }
            append('\n')
        }
        // Split by subject for the same reason the two ABOUT sections are
        // split: a list holding the baby's immunisation card and the mother's
        // postnatal check produced an answer that gave the baby a postpartum
        // recovery.
        val name = facts?.babyName ?: "the baby"
        records.filter { !it.isMother }.takeIf { it.isNotEmpty() }?.let { hers ->
            append("FILED RECORDS FOR ").append(name.uppercase()).append(":\n")
            hers.forEach { append("- ").append(it.title).append(": ").append(it.extract).append('\n') }
            append('\n')
        }
        records.filter { it.isMother }.takeIf { it.isNotEmpty() }?.let { hers ->
            append("FILED RECORDS FOR THE MOTHER:\n")
            hers.forEach { append("- ").append(it.title).append(": ").append(it.extract).append('\n') }
            append('\n')
        }
        if (passages.isNotEmpty()) {
            append("REFERENCE (the only source you may take general facts from):\n")
            passages.forEach { append("- ").append(it).append('\n') }
            append('\n')
        }
        append("QUESTION: ").append(question.trim())
    }
}

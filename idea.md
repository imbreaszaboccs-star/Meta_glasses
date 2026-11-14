1.  "Holistic and Connected Concept: FocusLens
Main Pitch: „Phones distract people with ADHD. Glasses give them back control. FocusLens transforms the visual and audio chaos in front of you into a single next step.“

Problem: The Mobile Phone Trap and Loss of Context
People with ADHD, dyslexia, or overwhelmed students struggle with three main obstacles: zoning out, short-term working memory failure, and executive paralysis (the inability to start or continue a task when stuck). Current tools and AI assistants require a person to pick up their phone. However, for an ADHD brain, unlocking the phone screen is a trap—they are immediately consumed by notifications, context-switching occurs, and returning to the original work takes an average of over 20 minutes.

Solution: Executive Copilot from a First-Person View
FocusLens moves assistance from the distracting phone into the natural field of view of smart glasses (e.g., Ray-Ban Meta with a display or purely audio response). Instead of ten complex features, it builds on a single immediate, frictionless interaction that keeps the user in the present moment working on paper, at the board, or at a desk.

How it works in practice (Technical architecture and battery saving):
The glasses do not record 90 minutes of video continuously, which would immediately drain the battery and overload memory (solving a crucial performance trade-off). Instead, an energy-efficient audio buffer runs in the background of the paired phone in the pocket, remembering only the last 60 seconds of audio in a rotating loop, while the glasses camera sleeps and waits for the trigger.

The moment a user experiences a "micro-lapse" of attention in a lecture or gets stuck on a math problem in their notebook, they simply tap the frame of the glasses:

1. Immediate context: The glasses take a photo of exactly what the user is looking at (the board, lecture notes, handwritten calculation, or desk clutter) and combine the image with the last 60 seconds of audio from the phone.
2. Micro-scaffolding (Against executive paralysis): The AI model does not generate a long paragraph of text that would further overwhelm a person with ADHD. On the glasses display (or in the ear), it sends one single specific next step. For example: „You dropped a minus sign on the third line, fix it and multiply the equation by two,“ or „The professor just explained the relationship for entropy, write down this formula from the board.“
3. Asynchronous safety net: At the same time, an enriched "context card" (photo + short audio excerpt + error correction) is saved to the phone application in the background. The user can return to it in the evening while studying without having to pick up the phone during the day.

Why this concept works (and wins competitions/hackathons):

* True inclusion (Accessibility): Solves a real neurological problem by removing visual noise and friction when seeking help.
* Perfect live demonstration: The product can be demonstrated in 30 seconds right at a desk. You just write an equation with an error or a chaotic to-do list on paper, put the glasses on the evaluator, let them tap the frame, and immediately show the hint in their field of view.
* Defensible architecture: The combination of a lightweight audio buffer on the phone and a single snapshot from the glasses is technically elegant, battery-friendly, cheap on computational power (API calls happen only upon a tap), and preserves the privacy of those around.

2. Market Research: Does something like this already exist?
The combination of smart glasses and AI is growing at a tremendous pace, but the specific focus on neurodiversity (ADHD) using the fusion of a backward audio buffer and a first-person photograph is an open niche in the market (Blue Ocean).

| Product / Category | What they do today | Why they fail for people with ADHD (Your advantage) |
| --- | --- | --- |
| General AI glasses (Ray-Ban Meta, Even Realities G1, Solos AirGo) | Can take a photo of a scene and answer a question („What do I see?“), translate text in real time, or display notifications and a teleprompter. | They lack a backward time loop (buffer) and pedagogical-executive logic. When a person with ADHD "zones out", they don't know what to ask because they don't know what they missed in the last minute. Moreover, general AI spews out overly long answers. |
| Memory AI wearables (Limitless Pendant, Friend, Plaud Note) | Record 100% of audio throughout the day and turn it into summaries of meetings or lectures. | They lack eyes (a camera) – they cannot see the formula on the board or the error in the notebook. Furthermore, they help ex-post (after the lecture), not in real time when a person gets stuck and needs an immediate "jump-start" step. |
| Educational mobile apps (Photomath, Gauth, ChatGPT Voice/Vision) | You take a photo of a math problem with your phone and the app shows you the complete solution process up to the result. | They require picking up the phone (risk of immediate distraction on social media). Instead of gently guiding to the next step, they immediately give away the entire solution, which destroys the learning process. |
| ADHD tools (Goblin.tools, Tiimo, Inflow) | Break down complex tasks into small subtasks (micro-scaffolding), plan time. | They are completely dependent on manual input into a phone or computer. At a moment of overwhelm, a person with ADHD does not have the capacity to transcribe their problem into an app. |

Conclusion of research: The individual building blocks exist (smart glasses, audio transcription, micro-scaffolding in apps), but no one has yet combined them into a touchless, context-aware copilot for instantly overcoming getting stuck without needing to pull out a mobile phone.

3. Real Impact: How much would this help people with ADHD?
For people with ADHD, this is not a "convenience feature", but a compensatory aid that targets the biological nature of their disorder directly. Real utility stands on four pillars:
4. Elimination of the "Context-Switching Tax":
5. Cognitive science research (e.g., Gloria Mark from UC Irvine) shows that after an interruption, it takes the average brain over 20 minutes to return to full focus. For people with ADHD, this number is even higher and often leads to completely abandoning the task. By keeping the user's eyes on their notebook and leaving the phone in their pocket, FocusLens saves students and workers dozens of minutes of net time with every single stall.
6. External working memory (Solving "Attention Blink"):
7. The ADHD brain has a demonstrably smaller working memory capacity. When solving a complex problem or listening to a lecture, a momentary impulse (a classmate coughing, one's own thought) is enough to break the thread. The person then sinks into frustration and anxiety because they don't understand what is happening on the board. A 60-second backward buffer connected to the image acts as an "Undo button" in real life. It reduces stress and allows the user to immediately jump back into the lecture.
8. Overcoming Executive Paralysis (Task Paralysis):
9. When a person with ADHD sees a complex problem (a long word problem, a messy desk, a blank sheet of paper), their brain evaluates the task as too demanding on dopamine and freezes. The ability of FocusLens to look at the problem through the user's eyes and tell them „Now do just this one small thing“ (so-called scaffolding) artificially replaces the missing executive functions of the prefrontal cortex.
10. Reducing Shame and Social Barrier:
11. Students with ADHD or dyslexia are often ashamed to raise their hand and ask the teacher: „Excuse me, I didn't catch the last minute, how did you get to this line?“ FocusLens gives them silent, discrete support right in their field of view, thereby increasing their self-confidence and independence.
"


- jak glasses tak wrist band mají akcelerometry a gyroskop 
- detekce jestli třeba přestal psat 

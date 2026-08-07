# اسکیل‌های نصب‌شده

۴۶ اسکیل، از شش مخزن. همه با پروانه‌های آزاد — متن هر پروانه در `LICENSES/` است.

| مخزن | پروانه | چه آورده |
|---|---|---|
| [nextlevelbuilder/ui-ux-pro-max-skill](https://github.com/nextlevelbuilder/ui-ux-pro-max-skill) | MIT | `ui-ux-pro-max`, `design`, `design-system`, `ui-styling`, `brand`, `banner-design` |
| [plugin87/ux-ui-agent-skills](https://github.com/plugin87/ux-ui-agent-skills) | MIT | ۱۷ اسکیل: `design-review`, `a11y-audit`, `redesign`, `design-tokens`, `ux-writing`, `token-build`, … |
| [meodai/skill.color-expert](https://github.com/meodai/skill.color-expert) | CC BY 4.0 | `color-expert` — علم رنگ، APCA، ساخت پالت |
| [obra/superpowers](https://github.com/obra/superpowers) | MIT | `systematic-debugging`, `test-driven-development`, `verification-before-completion`, بازبینی کد، برنامه‌ریزی |
| [mattpocock/skills](https://github.com/mattpocock/skills) | MIT | `diagnosing-bugs`, `code-review`, `tdd`, `codebase-design`, `improve-codebase-architecture` |
| [anthropics/skills](https://github.com/anthropics/skills) | Anthropic | `frontend-design`, `canvas-design`, `theme-factory`, `webapp-testing`, `skill-creator` |

## چرا این‌ها، برای این پروژه

سه ضعفِ ثابت‌شدهٔ این مخزن هدف گرفته شده‌اند:

**۱. باگ‌هایی که فقط روی دستگاه دیده می‌شوند.** بومِ وارونه و چرخشِ نشتی را هیچ‌کدام از ۱٬۹۰۰ تست ندید،
چون هیچ تستی روی پنجره present نمی‌کند. `systematic-debugging` و `verification-before-completion`
دقیقاً همین را اجباری می‌کنند: تا وقتی *تأیید* نشده، تمام‌شده نیست.

**۲. مدل هست، سیم‌کشی نیست.** نُه بار تکرار شد (`docs/GAP-ANALYSIS.md`). `codebase-design` و
`improve-codebase-architecture` همین را هدف می‌گیرند.

**۳. رابطی که ساده‌ترین کار را سخت کرد.** `design-review` و `redesign` و `ui-ux-pro-max` برای همین
هستند — و `a11y-audit` چیزی را می‌سنجد که تا امروز هیچ‌کس نسنجیده: کنتراست و اندازهٔ هدفِ لمس.

## نکتهٔ پروانه

این مخزن متن‌باز است، پس هر چیزی که وارد می‌شود باید پروانه‌اش همراهش بیاید. هر شش مخزن آزادند
(MIT یا CC BY 4.0)، متن‌ها در `LICENSES/` نگه داشته شده و هیچ‌کدام از هیچ اپ مرجعی نیامده — همان
تعهدی که پاورقی خودِ مخزن می‌دهد.

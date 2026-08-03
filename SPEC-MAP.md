# SPEC-MAP — نگاشت ماژول‌های کد به بخش‌های سند

هر ماژول کد به بخش مشخصی از `KARBIST-DESIGN-MASTER-v3.md` نگاشت می‌شود. ستون آخر می‌گوید کدام بخش
از آن مشخصات هنوز پیاده نشده و چرا.

نام ماژول‌های این مخزن با فهرست پیشنهادی سند (§۱۴.۱) یکی نیست ولی مرزهایشان یکی است؛ ستون «معادل
در سند» نگاشت را نشان می‌دهد.

| ماژول کد | معادل در سند | بخش مرجع | وضعیت | آنچه نیست |
|---|---|---|---|---|
| `core:model` | `core-document` | §۴.۱ مدل سند | لایه‌ای، نه DAG | گراف جهت‌دار؛ امروز پشتهٔ لایه با افکت‌های ترتیبی است |
| `core:codec` | `core-io` | §۱۲ فرمت‌ها | خواندن/نوشتن PNG، JPEG، WebP، HEIF، PDF؛ خواندن PSD | `.kbd` (فرمت بومی امروز `.pxl` است)، TIFF، AVIF، EXR، RAW |
| `core:text` | `core-text` | §۵ تایپوگرافی فارسی | shaping از استک اندروید، کشیده، دوجهته، **خوشهٔ متصل (§۶.۹.۱)** | HarfBuzz بسته‌بندی‌شده در NDK — §۵.۲.الف |
| `core:fonts` | `core-text` | §۵.۲.ج.۸ مدیریت فونت | اسکن، گروه‌بندی، جست‌وجو، پیش‌نمایش فارسی | تشخیص ویژگی‌های OpenType هر فونت و هشدار |
| `core:render` | `core-render` | §۴.۲ لایهٔ رندر، §۷.۶ لایه‌های تنظیم | ۲۷ حالت ترکیب، ۲۲ لایهٔ تنظیم، Layer Styles | کاشی‌بندی، ۳۲ بیت float خطی، OCIO/ACEScg |
| `core:canvas` | `core-render` | §۷.۹ سند و کارگاه | خط‌کش، راهنما، چسبندگی، چرخش نما | Artboards متعدد |
| `core:imaging` | `core-raster` | §۷.۷ فیلترها | تاری، شارپ، نویز، سایه/هایلایت، استایلایز، بافت رویه‌ای | Liquify، Face-Aware Liquify، Blur Gallery کامل، Camera Raw |
| `core:paint` | `core-raster` | §۷.۱ موتور قلم | نوک، فاصله، فشار، نرم‌سازی، پراکندگی، بافت | نقاشی متقارن، ایمپورت ABR، Dual Brush |
| `core:vector` | `core-vector` | §۸.۱ مسیر و هندسه | بزیه، بولین، آفست، پروفایل عرض، SVG | Pathfinder کامل، Shape Builder، Image Trace |
| `core:mesh` | `core-3d` | §۶ تایپوگرافی سه‌بعدی | مثلث‌بندی، اکسترود، بِوِل، **محافظ شعاع فارسی (§۶.۲)**، **نقطه و اعراب مستقل (§۶.۹.۳)**، PBR، رستریزه | Filament، IBL، مسیریاب پرتو |
| `core:editor` | `core-document` | §۴.۱، §۱۳ | حالت ویرایشگر، تاریخچه، انتخاب، کتابخانه | تاریخچهٔ درختی/شاخه‌ای |
| `core:ai` | `core-ai` | §۱۰ لایهٔ انتزاع | `SegmentationModel` → `SubjectCutout` با مسیر کلاسیک | Model Router و Provider Adapter به‌صورت جدا |
| `engine:android` | `core-render` + پل | §۴.۴ استک اندروید | OpenGL ES، ONNX Runtime، کدک‌های پلتفرم | Vulkan، NDK/C++، JNI |
| `app:android` | `app` + `design-system` + `feature-*` | §۱۳ رابط کاربری | سیستم طراحی «کارگاه»، خانه، ویرایشگر، ۲۰ پنل، **نوار خوشه‌ها (ایدهٔ ۳)** | تفکیک `design-system` به ماژول Gradle مستقل |

---

## سیستم طراحی — نگاشت بند به بند

جدول توکن‌های §۶ سند در برابر `app/android/.../Theme.kt`:

| بند سند | توکن کد | مطابق؟ |
|---|---|---|
| `canvas.backdrop #0B0C0E` | `Ink.Ground` | ✅ |
| `surface.1 #141619` | `Ink.Chrome` | ✅ |
| `surface.2 #1C1F23` | `Ink.ChromeRaised` | ✅ |
| `surface.3 #262A2F` | `Ink.ChromeSunken` | ✅ |
| `border.subtle #2E3338` | `Ink.Divider` | ✅ |
| `border.strong #3D444B` | `Ink.Outline` | ✅ |
| `text.primary #F2F4F6` | `Ink.Text` | ✅ |
| `text.secondary #A3ABB4` | `Ink.TextMuted` | ✅ |
| `text.disabled #5C646D` | `Ink.TextDisabled` | ✅ |
| `accent.primary #E8A33D` | `Ink.Accent` | ✅ |
| `accent.pressed #C9873963` | `Ink.AccentPressed` | ✅ |
| `accent.subtle #E8A33D1A` | `Ink.AccentSoft` | ✅ |
| `secondary.teal #3DCCC0` | `Ink.Selection` | ✅ |
| `state.success/warning/error` | `Ink.Success/Warning/Danger` | ✅ |
| `overlay.scrim #000000B3` | `Ink.Scrim` | ✅ |
| تم روشن (۵ مقدار) | `Palette.Light` | ✅ + بقیه مشتق‌شده |
| فونت Vazirmatn Variable | `res/font/vazirmatn.ttf` | ✅ بسته‌بندی‌شده، SIL OFL |
| `display 22/600/1.4` … `caption 11/400/1.4` | `typography` | ✅ |
| `numeric 14/500/tnum` | `NumericStyle` + `Numeric()` | ✅ |
| ارقام لاتین در فیلد فنی | `Digits.technical` / `Digits.prose` | ✅ |
| فاصله `4·8·12·16·24·32·48` | `Space` | ✅ |
| شعاع `button 8 · card 12 · sheet 20 · chip 999` | `Corners` | ✅ |
| هدف لمسی ≥ ۴۸dp | `Space.touch` | ✅ |
| `150/200/250ms` + `easeOutCubic` | `Motion` | ✅ |
| نوار تاریخچه ۵۶dp | `Frame.history` | ✅ |
| ریبون بافتار ۸۸dp | `Frame.ribbon` | ✅ |
| داک ۶۴dp | `Frame.dock` | ✅ |
| پنل بازرس کشویی از لبهٔ چپ | — | ❌ امروز شیت از پایین است |
| بدون گرادیان تزئینی | — | ✅ حذف شد |
| آیکون تک‌رنگ خطی | `Icons.Outlined.*` | ⚠️ ضخامت خط از مجموعهٔ Material می‌آید، نه ۱.۵dp دقیق |
| آینه‌سازی آیکون‌های جهت‌دار | `Icons.AutoMirrored.*` | ✅ |

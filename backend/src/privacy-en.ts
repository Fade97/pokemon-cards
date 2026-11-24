// English privacy policy (#70). Keep it in step with privacy-de.ts and with what the app and the Worker do; the
// data inventory behind it is in issue #70.

import type { PrivacyText } from "./privacy";

export const privacyEn: PrivacyText = {
  lang: "en",
  title: "Privacy policy",
  languageName: "English",
  updated: "Last updated:",
  body: (c) => `
<p>This policy explains which personal data the Android app <strong>${c.app}</strong> (“the app”) and its server
process, why, and what rights you have. The short version: the app has no account, no ads and no analytics of its
own. Your collection stays on your phone. Pictures leave the phone only if you turn on “Contribute my scans”.</p>

<h2>1. Who is responsible</h2>
<p>Controller under the EU General Data Protection Regulation (GDPR):</p>
<address>${c.name}<br>${c.address}<br>Email: ${c.emailLink}</address>
<p>There is no data protection officer; none is required. For any privacy question, write to the email address above.</p>

<h2>2. What stays on your phone</h2>
<p>The following is processed only on your phone. We do not receive it.</p>
<ul>
<li><strong>Camera.</strong> The app asks for the camera permission to scan cards. Card detection, recognition and
reading the collector number all run on the phone. Camera frames are not stored. Only the cropped picture of a card
the app was unsure about is kept on the phone for the review queue, and a crop leaves the phone only if you contribute
scans (section 6).</li>
<li><strong>Your collection:</strong> copies, collections, condition, grade, purchase price and date, the review
queue, saved prices and your settings.</li>
<li><strong>Your own photos</strong> of copies, which you pick with Android’s photo picker. The app gets only the photos
you pick, not access to your gallery.</li>
<li><strong>Exports</strong> (JSON/CSV) are saved where you choose.</li>
</ul>
<p>A copy of your collection and the settings are part of your phone’s Android backup and device transfer, if you have
them turned on. Your consent to contribute scans and the withdrawal keys of your contributions (section 6) are not:
they stay on the phone they were created on. That backup is a service of Google (or your phone’s maker) under your account and their terms; we
have no access to it. Your own photos and card pictures are not included.</p>
<p>Uninstalling the app deletes all of this from the phone, except exports you saved outside the app.</p>

<h2>3. Language packs and prices</h2>
<p>The app downloads language packs (card data and recognition models) and current card prices from our server. The
app checks for new packs about once a day in the background. It fetches prices for the cards in your collection
when you open it or tap “Update prices now”, and for a card whose details you open.</p>
<ul>
<li><strong>Data:</strong> your IP address, the time, the address requested (for example the pack file), technical
headers (the app sends a generic user agent, <code>okhttp</code>, without any device details) and, for prices, the
IDs of up to 100 cards per request.</li>
<li><strong>Purpose:</strong> to deliver packs and prices, and to keep the service working: requests are limited per
IP address to prevent abuse.</li>
<li><strong>Legal basis:</strong> Art. 6(1)(f) GDPR. Our legitimate interest is to provide the functions you use and to
protect the server from overload and abuse.</li>
<li><strong>Retention:</strong> our server code stores none of this. The IP address is used as a key for rate limiting
for about a minute. The card IDs of a price request are only used to answer it. Our hosting provider Cloudflare
logs a request only when our server code reports an error; such a log entry contains the IP address, the location
Cloudflare derives from it (country, region, city), the user agent, the requested address and the request headers.
Cloudflare deletes these logs automatically after at most 7 days (3 days on our current plan).</li>
</ul>
<p>The same applies when you open this page.</p>

<h2>4. Card images from TCGdex</h2>
<p>Card pictures are loaded from the image service of TCGdex (<a href="https://tcgdex.dev">tcgdex.dev</a>), an
independent open-source project, and cached on your phone. When the app loads an image, your phone connects to
TCGdex’s servers. TCGdex receives your IP address and the address of the image (which shows which card is
displayed). We do not control what TCGdex logs. The legal basis is Art. 6(1)(f) GDPR: showing a picture of the card
is part of the app, and the app does not bundle card art. If you turn on “Prefer my own photos”, your own photo is
shown instead wherever you added one.</p>

<h2>5. Text recognition by Google ML Kit</h2>
<p>To read the collector number, the app uses Google’s ML Kit text recognition. The recognition models are part of the
app and run on the phone; the camera images and the recognised text are processed on the device and are not sent
anywhere. On its own, the ML Kit SDK would send diagnostic and usage data with a per-installation identifier to Google
(<a href="https://developers.google.com/ml-kit/android-data-disclosure">ML Kit data disclosure</a>). The app removes
the part of the SDK that sends this data: the SDK deletes it on the phone, and nothing reaches Google or us.</p>

<h2>6. Contributing scans (optional)</h2>
<p>You can help train the card recognition by turning on <strong>Settings → Improve recognition → Contribute my scans</strong>.
It is off by default, and it starts only after you accept the consent text in the app.</p>
<h3>What is sent</h3>
<ul>
<li>for each scanned card added to your collection (automatically after a confident scan, or after you confirm it in
the review queue), the cropped and straightened picture of the card, as a JPEG. All metadata (EXIF, location,
camera details) is removed on the phone and again on the server;</li>
<li>the card’s ID and the language of the printed card, as recognised or confirmed;</li>
<li>if you picked a different card than the scanner’s first guess: the ID and language of that guess, so the
recognition learns from the mistake;</li>
<li>the version of the consent text you accepted;</li>
<li>a random ID and a random secret (the withdrawal key) the app creates for each picture. The server stores only a
hash of the key.</li>
</ul>
<p>Not sent and not stored: your name, account, location, device data or advertising ID. Your IP address is needed
for the upload and is used only for rate limiting (see section 3); it is not stored with the picture.</p>
<p>A card picture usually says nothing about you. We still treat contributions as personal data: the upload comes
from your connection, and a picture could show something by accident, such as a finger. Please make sure only the
card is visible.</p>
<h3>Purpose and legal basis</h3>
<p>The pictures are used only to train and test the app’s card recognition models. They are not published: the card
artwork in them belongs to The Pokémon Company. What is published as open source is what the models learn from the
pictures (the model weights and the reference index), not the pictures. The legal basis is your consent, Art. 6(1)(a)
GDPR. Contributing is only for people aged 16 or older, because consent under 16 would need a parent or guardian
(Art. 8 GDPR); the app asks you to confirm that you are 16 or older.</p>
<h3>Recipients</h3>
<p>The pictures are stored privately with our hosting provider Cloudflare (section 7) and used only by us, on our own
computers, for training and testing. Before a picture is used we look at it: a rejected picture (no card, the wrong
card, more than the card visible) is deleted, and we keep only the record without the picture, as after a
withdrawal.</p>
<h3>Withdrawal and retention</h3>
<ul>
<li>You can withdraw your consent at any time, for one picture or for all, under <strong>Settings → My
contributions</strong>. The app sends the withdrawal when your phone is online; our server then deletes the picture at once, and it is not used for training again. The withdrawal does not affect processing before it (Art. 7(3) GDPR).</li>
<li><strong>Models trained before the withdrawal keep what they learned from the picture.</strong> The picture itself
cannot be recovered from a model.</li>
<li>Turning the switch off deletes the pictures that are still waiting to be sent. Pictures already sent stay until
you withdraw them.</li>
<li>Otherwise a picture is kept as long as we train and test the recognition with it. After a withdrawal we keep only a record without
the picture (ID, hash of the withdrawal key, card IDs and languages, consent version, file type, size and dimensions,
checksum and times), so that a picture that
was withdrawn is never used again and an upload still on its way is refused.</li>
<li><strong>Important:</strong> only the app on your phone knows which pictures are yours. If you uninstall the app,
clear its data or move to a new phone, we can no longer tell which pictures you sent, because we store no account,
IP address or device data with them (Art. 11 GDPR), and the Android backup does not include the withdrawal keys.
Withdraw the pictures in the app before you uninstall it or change phones.</li>
</ul>


<h2>7. Hosting: Cloudflare</h2>
<p>Our server runs on Cloudflare Workers, with storage in Cloudflare R2 and D1. The provider is Cloudflare, Inc.,
101 Townsend St., San Francisco, CA 94107, USA. Cloudflare processes the data described in sections 3 and 6 on our
behalf under a data processing agreement (Art. 28 GDPR). Cloudflare runs a worldwide network, so data may be
processed outside the EU, including in the USA. Cloudflare is certified under the EU-U.S. Data Privacy Framework
(adequacy decision, Art. 45 GDPR), and its data processing agreement also contains the EU standard contractual
clauses (Art. 46 GDPR).</p>

<h2>8. Google Play and links</h2>
<ul>
<li>When you install the app from Google Play, Google processes data about the download and, if you allow it, crash
and performance data (Android vitals) under its own responsibility. We see only aggregated statistics.</li>
<li>Links to Cardmarket and TCGplayer, and to this page, open in your browser. Those sites are responsible for what they process.</li>
</ul>

<h2>9. What we don’t do</h2>
<p>No advertising, no selling of data, no profiling, no tracking across apps or websites, no automated decisions with
legal effect (Art. 22 GDPR). All connections of the app use HTTPS.</p>

<h2>10. Your rights</h2>
<p>Under the GDPR you have the right to access (Art. 15), rectification (Art. 16), erasure (Art. 17), restriction of
processing (Art. 18), data portability (Art. 20) and to withdraw a consent at any time with effect for the future
(Art. 7(3)).</p>
<p><strong>Right to object (Art. 21 GDPR):</strong> where we process data on the basis of Art. 6(1)(f) GDPR, you can
object at any time on grounds relating to your particular situation.</p>
<p>To use these rights, write to ${c.emailLink}. For contributed pictures, the app is the quickest way: only the app
knows which pictures are yours (section 6). You can also complain to a data protection supervisory authority, in
particular in the EU country where you live or work (Art. 77 GDPR).</p>

<h2>11. Changes</h2>
<p>We update this policy when the app or the server changes what they do with data, for example before accounts or
sync are introduced. The date at the top shows the current version. If a change needs your consent, the app asks for
it again.</p>
`,
};

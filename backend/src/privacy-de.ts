// German privacy policy (#70). Keep it in step with privacy-en.ts and with what the app and the Worker do; the
// data inventory behind it is in issue #70.

import type { PrivacyText } from "./privacy";

export const privacyDe: PrivacyText = {
  lang: "de",
  title: "Datenschutzerklärung",
  languageName: "Deutsch",
  updated: "Stand:",
  body: (c) => `
<p>Diese Erklärung beschreibt, welche personenbezogenen Daten die Android-App <strong>${c.app}</strong> („die App“) und
ihr Server verarbeiten, wozu, und welche Rechte du hast. Kurz gesagt: Die App hat kein Konto, keine Werbung und keine
eigene Analyse. Deine Sammlung bleibt auf deinem Handy. Bilder verlassen das Handy nur, wenn du „Contribute my scans“
(Scans beitragen) einschaltest.</p>

<h2>1. Verantwortlicher</h2>
<p>Verantwortlicher im Sinne der Datenschutz-Grundverordnung (DSGVO):</p>
<address>${c.name}<br>${c.address}<br>E-Mail: ${c.emailLink}</address>
<p>Ein Datenschutzbeauftragter ist nicht benannt und nicht vorgeschrieben. Bei Fragen zum Datenschutz schreib an die
E-Mail-Adresse oben.</p>

<h2>2. Was auf deinem Handy bleibt</h2>
<p>Folgendes wird nur auf deinem Handy verarbeitet. Wir erhalten es nicht.</p>
<ul>
<li><strong>Kamera.</strong> Die App fragt nach der Kamera-Berechtigung, um Karten zu scannen. Kartenerkennung,
Zuordnung und das Lesen der Kartennummer laufen auf dem Handy. Kamerabilder werden nicht gespeichert. Nur das
zugeschnittene Bild einer Karte, bei der die App unsicher war, bleibt für die Prüfliste auf dem Handy, und ein
Zuschnitt verlässt das Handy nur, wenn du Scans beiträgst (Abschnitt 6).</li>
<li><strong>Deine Sammlung:</strong> Exemplare, Sammlungen, Zustand, Grading, Kaufpreis und -datum, die Prüfliste,
gespeicherte Preise und deine Einstellungen.</li>
<li><strong>Eigene Fotos</strong> deiner Exemplare, die du mit der Fotoauswahl von Android auswählst. Die App erhält nur
die ausgewählten Fotos, keinen Zugriff auf deine Galerie.</li>
<li><strong>Exporte</strong> (JSON/CSV) werden dort gespeichert, wo du es auswählst.</li>
</ul>
<p>Die Sammlungsdatenbank und die Einstellungen sind Teil der Android-Sicherung und der Geräteübertragung deines
Handys, wenn du diese eingeschaltet hast. Diese Sicherung ist ein Dienst von Google (oder des Herstellers deines
Handys) unter deinem Konto und deren Bedingungen; wir haben keinen Zugriff darauf. Eigene Fotos und Kartenbilder sind
nicht enthalten.</p>
<p>Wenn du die App deinstallierst, wird all das vom Handy gelöscht, außer Exporten, die du außerhalb der App
gespeichert hast.</p>

<h2>3. Sprachpakete und Preise</h2>
<p>Die App lädt Sprachpakete (Kartendaten und Erkennungsmodelle) und aktuelle Kartenpreise von unserem Server. Etwa
einmal am Tag sucht die App im Hintergrund nach neuen Paketen. Preise für die Karten deiner Sammlung lädt sie, wenn
du die Sammlung öffnest oder auf „Update prices now“ (Preise jetzt aktualisieren) tippst, und für eine Karte, deren
Details du öffnest.</p>
<ul>
<li><strong>Daten:</strong> deine IP-Adresse, der Zeitpunkt, die abgerufene Adresse (zum Beispiel die Paketdatei),
technische Header (die App sendet eine allgemeine Kennung, <code>okhttp</code>, ohne Angaben zum Gerät) und bei
Preisen die IDs von bis zu 100 Karten je Anfrage.</li>
<li><strong>Zweck:</strong> Pakete und Preise ausliefern und den Dienst funktionsfähig halten: Anfragen werden je
IP-Adresse begrenzt, um Missbrauch zu verhindern.</li>
<li><strong>Rechtsgrundlage:</strong> Art. 6 Abs. 1 lit. f DSGVO. Unser berechtigtes Interesse ist es, die Funktionen
bereitzustellen, die du nutzt, und den Server vor Überlastung und Missbrauch zu schützen.</li>
<li><strong>Speicherdauer:</strong> Unser Servercode speichert nichts davon. Die IP-Adresse dient etwa eine Minute lang
als Schlüssel für die Begrenzung der Anfragen. Die Karten-IDs einer Preisanfrage werden nur zur Beantwortung
verwendet. Unser Hosting-Anbieter Cloudflare
protokolliert eine Anfrage nur, wenn unser Servercode einen Fehler meldet; ein solcher Eintrag enthält die
IP-Adresse, den daraus abgeleiteten Ort (Land, Region, Stadt), die Kennung des Programms, die abgerufene Adresse und
die Header der Anfrage. Cloudflare löscht diese Protokolle automatisch nach höchstens 7 Tagen (in unserem aktuellen
Tarif nach 3 Tagen).</li>
</ul>
<p>Dasselbe gilt, wenn du diese Seite aufrufst.</p>

<h2>4. Kartenbilder von TCGdex</h2>
<p>Kartenbilder werden vom Bilddienst von TCGdex (<a href="https://tcgdex.dev">tcgdex.dev</a>) geladen, einem
unabhängigen Open-Source-Projekt, und auf deinem Handy zwischengespeichert. Lädt die App ein Bild, verbindet sich dein
Handy mit den Servern von TCGdex. TCGdex erhält deine IP-Adresse und die Adresse des Bildes (daraus geht hervor, welche
Karte angezeigt wird). Was TCGdex protokolliert, liegt nicht in unserer Hand. Rechtsgrundlage ist Art. 6 Abs. 1
lit. f DSGVO: Das Bild der Karte zu zeigen, gehört zur App, und die App enthält selbst keine Kartenbilder. Wenn du
„Prefer my own photos“ (eigene Fotos bevorzugen) einschaltest, wird stattdessen dein eigenes Foto gezeigt, wo du eines
hinzugefügt hast.</p>

<h2>5. Texterkennung mit Google ML Kit</h2>
<p>Um die Kartennummer zu lesen, nutzt die App die Texterkennung von Google ML Kit. Die Erkennungsmodelle sind Teil der
App und laufen auf dem Handy; Kamerabilder und erkannter Text werden auf dem Gerät verarbeitet und nirgendwohin
gesendet. Von sich aus würde das ML-Kit-SDK Diagnose- und Nutzungsdaten mit einer Kennung je Installation an Google
senden (<a href="https://developers.google.com/ml-kit/android-data-disclosure">ML Kit data disclosure</a>). Die App
entfernt den Teil des SDK, der diese Daten sendet: Das SDK löscht sie auf dem Handy, und weder Google noch wir erhalten
sie.</p>

<h2>6. Scans beitragen (freiwillig)</h2>
<p>Du kannst beim Training der Kartenerkennung helfen, indem du <strong>Settings → Improve recognition → Contribute
my scans</strong> einschaltest. Das ist standardmäßig aus und beginnt erst, wenn du dem Einwilligungstext in der App
zustimmst.</p>
<h3>Was gesendet wird</h3>
<ul>
<li>für jede gescannte Karte, die in deine Sammlung kommt (automatisch nach einem sicheren Scan oder nachdem du sie
in der Prüfliste bestätigt hast), das zugeschnittene und entzerrte Bild der Karte als JPEG. Alle Metadaten (EXIF,
Standort, Kameradaten) werden auf dem Handy und noch einmal auf dem Server entfernt;</li>
<li>die ID der Karte und die Sprache der gedruckten Karte, so wie sie erkannt oder bestätigt wurden;</li>
<li>die Version des Einwilligungstextes, dem du zugestimmt hast;</li>
<li>eine zufällige ID und ein zufälliger geheimer Schlüssel (Widerrufsschlüssel), die die App für jedes Bild erzeugt.
Der Server speichert nur einen Hash des Schlüssels.</li>
</ul>
<p>Nicht gesendet und nicht gespeichert werden: dein Name, ein Konto, dein Standort, Gerätedaten oder eine
Werbe-ID. Deine IP-Adresse ist für das Hochladen nötig und wird nur zur Begrenzung der Anfragen verwendet (siehe
Abschnitt 3); sie wird nicht mit dem Bild gespeichert.</p>
<p>Ein Kartenbild sagt in der Regel nichts über dich aus. Wir behandeln Beiträge trotzdem als personenbezogene Daten:
Das Hochladen kommt von deinem Anschluss, und auf einem Bild könnte versehentlich etwas zu sehen sein, etwa ein
Finger. Achte bitte darauf, dass nur die Karte zu sehen ist.</p>
<h3>Zweck und Rechtsgrundlage</h3>
<p>Die Bilder dienen nur dazu, die Modelle der Kartenerkennung der App zu trainieren und zu testen. Sie werden nicht
veröffentlicht: Die Kartenmotive darauf gehören The Pokémon Company. Als Open Source veröffentlicht wird, was die
Modelle aus den Bildern lernen (die Modellgewichte und der Referenzindex), nicht die Bilder. Rechtsgrundlage ist deine
Einwilligung, Art. 6 Abs. 1 lit. a DSGVO. Unter 16 braucht die Einwilligung deine Eltern oder Erziehungsberechtigten
(Art. 8 DSGVO); die App lässt dich bestätigen, dass du mindestens 16 bist oder sie zugestimmt haben.</p>
<h3>Empfänger</h3>
<p>Die Bilder werden nicht öffentlich bei unserem Hosting-Anbieter Cloudflare gespeichert (Abschnitt 7) und nur von
uns auf eigenen Rechnern zum Trainieren und Testen genutzt. Bevor ein Bild genutzt wird, sehen wir es uns an: Ein
abgelehntes Bild (keine Karte, falsche Karte, mehr als die Karte zu sehen) löschen wir und behalten nur den Eintrag
ohne Bild wie nach einem Widerruf.</p>
<h3>Widerruf und Speicherdauer</h3>
<ul>
<li>Du kannst deine Einwilligung jederzeit widerrufen, für ein Bild oder für alle, unter <strong>Settings → My
contributions</strong>. Ein widerrufenes Bild wird sofort von unserem Server gelöscht und nicht mehr zum Training
genutzt. Der Widerruf berührt nicht die Rechtmäßigkeit der Verarbeitung bis dahin (Art. 7 Abs. 3
DSGVO).</li>
<li><strong>Modelle, die vor dem Widerruf trainiert wurden, behalten, was sie aus dem Bild gelernt haben.</strong> Das
Bild selbst lässt sich aus einem Modell nicht zurückgewinnen.</li>
<li>Schaltest du die Funktion aus, werden die Bilder gelöscht, die noch auf das Senden warten. Bereits gesendete Bilder
bleiben, bis du sie widerrufst.</li>
<li>Ansonsten wird ein Bild gespeichert, solange wir die Erkennung damit trainieren und testen. Nach einem Widerruf behalten wir nur
einen Eintrag ohne Bild (ID, Hash des Widerrufsschlüssels, Karten-ID, Sprache, Version der Einwilligung, Dateityp,
-größe und Bildmaße, Prüfsumme und Zeitpunkte),
damit ein widerrufenes Bild nie wieder genutzt und ein noch laufendes Hochladen abgewiesen wird.</li>
<li><strong>Wichtig:</strong> Nur die App auf deinem Handy weiß, welche Bilder von dir sind. Deinstallierst du die App
(oder löschst ihre Daten), ohne sie aus einer Sicherung wiederherzustellen, können wir nicht mehr erkennen, welche
Bilder du gesendet hast, weil wir kein Konto, keine IP-Adresse und keine Gerätedaten dazu speichern (Art. 11 DSGVO).
Widerrufe die Bilder in der App, bevor du sie deinstallierst.</li>
</ul>


<h2>7. Hosting: Cloudflare</h2>
<p>Unser Server läuft auf Cloudflare Workers, die Daten liegen in Cloudflare R2 und D1. Anbieter ist Cloudflare, Inc.,
101 Townsend St., San Francisco, CA 94107, USA. Cloudflare verarbeitet die in den Abschnitten 3 und 6 genannten Daten
in unserem Auftrag auf Grundlage eines Auftragsverarbeitungsvertrags (Art. 28 DSGVO). Cloudflare betreibt ein
weltweites Netz, daher können Daten auch außerhalb der EU verarbeitet werden, auch in den USA. Cloudflare ist nach dem
EU-US Data Privacy Framework zertifiziert (Angemessenheitsbeschluss, Art. 45 DSGVO); der Auftragsverarbeitungsvertrag
enthält zusätzlich die EU-Standardvertragsklauseln (Art. 46 DSGVO).</p>

<h2>8. Google Play und Links</h2>
<ul>
<li>Wenn du die App über Google Play installierst, verarbeitet Google Daten zum Download und, wenn du es erlaubst,
Absturz- und Leistungsdaten (Android Vitals) in eigener Verantwortung. Wir sehen nur zusammengefasste Statistiken.</li>
<li>Links zu Cardmarket und TCGplayer sowie zu dieser Seite öffnen sich in deinem Browser. Für die Verarbeitung dort sind diese
Seiten verantwortlich.</li>
</ul>

<h2>9. Was wir nicht tun</h2>
<p>Keine Werbung, kein Verkauf von Daten, kein Profiling, kein Tracking über Apps oder Websites hinweg, keine
automatisierten Entscheidungen mit rechtlicher Wirkung (Art. 22 DSGVO). Alle Verbindungen der App nutzen HTTPS.</p>

<h2>10. Deine Rechte</h2>
<p>Nach der DSGVO hast du das Recht auf Auskunft (Art. 15), Berichtigung (Art. 16), Löschung (Art. 17), Einschränkung
der Verarbeitung (Art. 18), Datenübertragbarkeit (Art. 20) und darauf, eine Einwilligung jederzeit mit Wirkung für die
Zukunft zu widerrufen (Art. 7 Abs. 3).</p>
<p><strong>Widerspruchsrecht (Art. 21 DSGVO):</strong> Soweit wir Daten auf Grundlage von Art. 6 Abs. 1 lit. f DSGVO
verarbeiten, kannst du aus Gründen, die sich aus deiner besonderen Situation ergeben, jederzeit widersprechen.</p>
<p>Um diese Rechte auszuüben, schreib an ${c.emailLink}. Für beigetragene Bilder geht es in der App am schnellsten:
Nur die App weiß, welche Bilder von dir sind (Abschnitt 6). Außerdem kannst du dich bei einer
Datenschutz-Aufsichtsbehörde beschweren, insbesondere in dem EU-Land, in dem du wohnst oder arbeitest
(Art. 77 DSGVO).</p>

<h2>11. Änderungen</h2>
<p>Wir passen diese Erklärung an, wenn App oder Server anders mit Daten umgehen, zum Beispiel bevor Konten oder
Synchronisierung eingeführt werden. Das Datum oben zeigt den aktuellen Stand. Braucht eine Änderung deine
Einwilligung, fragt die App erneut danach.</p>
`,
};

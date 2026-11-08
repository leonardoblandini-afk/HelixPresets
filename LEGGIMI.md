# HX Presets

Legge l'elenco dei preset di una HX Stomp collegata in USB a un tablet Android e lo mostra in una lista selezionabile.

## Uso
1. Apri la cartella in Android Studio e fai la sincronizzazione Gradle (il tablet deve supportare USB host).
2. Collega la HX Stomp al tablet con un cavo USB (OTG se serve), accetta il permesso USB.
3. Tocca "Collega e leggi i preset". In basso compaiono le ultime righe del traffico USB.

## Cosa è verificato
`verifica/ReplayTest.java` fa rispondere la cattura al posto della HX Stomp: legge 126 preset identici alla decodifica
indipendente e invia le stesse richieste della cattura.

    javac --release 8 -d out app/src/main/java/it/helixpresets/protocol/*.java verifica/ReplayTest.java
    java -cp out ReplayTest verifica/dati_corretto.txt verifica/attesi.txt

## Cosa NON è verificato
- La compilazione dell'app Android e la prova su una HX Stomp reale (la parte USB e la schermata non sono mai state eseguite).
- La selezione nella lista non cambia preset sulla HX Stomp: quel comando non è nella cattura.

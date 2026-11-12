# HX Presets

Legge l'elenco dei preset di una HX Stomp collegata in USB a un tablet Android e lo mostra in una lista selezionabile.

## Uso
1. Apri la cartella in Android Studio e fai la sincronizzazione Gradle (il tablet deve supportare USB host).
2. Collega la HX Stomp al tablet con un cavo USB (OTG se serve), accetta il permesso USB.
3. Tocca "Collega e leggi i preset". In basso compaiono le ultime righe del traffico USB.
4. Tocca un preset nella lista: l'app lo seleziona sulla HX Stomp (comando 20, posizione nell'elenco), poi ne legge il contenuto (comandi 23 e 22) e scrive sui tre bottoni di quella riga i nomi dei suoi snapshot.
5. Tocca uno dei tre bottoni snapshot di una riga: l'app seleziona il preset (se non lo è già) e passa a quello snapshot (comando 88 con {92: n}, n da 0 a 2), poi rilegge il preset corrente per confermare. Lo snapshot attivo è evidenziato con un pallino e il grassetto.

## Cosa è verificato
`verifica/ReplayTest.java` fa rispondere le catture al posto della HX Stomp: legge 126 preset identici alla decodifica
indipendente, invia le stesse richieste delle catture e, nella seconda, la stessa selezione (preset in posizione 1) e legge i nomi dei suoi snapshot (CLEAN, CRUNCH, LEAD). `SnapshotTest` confronta i tre comandi di cambio snapshot (1→2→3→1) con i byte che l'app genera.

    javac --release 8 -d out app/src/main/java/it/helixpresets/protocol/*.java verifica/ReplayTest.java
    java -cp out ReplayTest verifica/dati_corretto.txt verifica/attesi.txt
    java -cp out ReplayTest verifica/dati_selezione.txt verifica/attesi.txt 1 "CLE-CRU-LEA(S) | CLEAN, CRUNCH, LEAD | attivo 1"
    java -cp out SnapshotTest verifica/dati_snapshot.txt

## Cosa NON è verificato
- La compilazione dell'app Android e la prova su una HX Stomp reale (la parte USB e la schermata non sono mai state eseguite).
- Il numero a sinistra di ogni preset è la posizione nell'elenco (parte da 0), non necessariamente il numero mostrato dal pannello.
- La selezione è stata ricavata da una sola cattura (posizione 1): la posizione 0 e le altre usano lo stesso comando ma non sono state osservate.
- Il cambio snapshot è provato solo sui byte della richiesta e sulle conferme della cattura (che inizia a sessione già aperta, quindi non si può riprodurre tutta la sequenza); l'app non è mai stata eseguita su una HX Stomp.
- I nomi degli snapshot si leggono solo dopo aver selezionato il preset (cambia il suono sulla HX Stomp): non c'è una lettura di tutti i 126 preset in blocco.

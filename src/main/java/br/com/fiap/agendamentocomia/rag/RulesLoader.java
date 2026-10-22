package br.com.fiap.agendamentocomia.rag;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.reader.pdf.PagePdfDocumentReader;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;

@Component
public class RulesLoader implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(RulesLoader.class);

    private static final String RULES_FILE = "doc/regras.pdf";

    private final VectorStore vectorStore;

    public RulesLoader(VectorStore vectorStore) {
        this.vectorStore = vectorStore;
    }

    @Override
    public void run(ApplicationArguments args) {
        // o pgvector persiste os dados, então só carrega se estiver vazio
        if (!vectorStore.similaritySearch("regras").isEmpty()) {
            log.info("Regras já estão no vector store, pulando carga.");
            return;
        }

        Resource resource = new ClassPathResource(RULES_FILE);
        if (!resource.exists()) {
            log.warn("Arquivo de regras '{}' não encontrado em resources.", RULES_FILE);
            return;
        }

        var reader = new PagePdfDocumentReader(resource);
        List<Document> chunks = new TokenTextSplitter().apply(reader.get());
        vectorStore.add(chunks);
        log.info("{} trechos de regras carregados no vector store.", chunks.size());
    }
}
package com.byeolnaerim.mongodb;


import org.jspecify.annotations.Nullable;
import org.springframework.data.mongodb.ReactiveMongoTransactionManager;
import org.springframework.data.mongodb.core.ReactiveMongoTemplate;
import org.springframework.transaction.reactive.TransactionalOperator;

public interface MongoTemplateResolver<K> {
 ReactiveMongoTemplate getTemplate(K key);
 @Nullable ReactiveMongoTransactionManager getTxManager(K key);
 @Nullable TransactionalOperator getTxOperator(K key);
}


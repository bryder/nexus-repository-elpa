/*
 * Sonatype Nexus (TM) Open Source Version
 * Copyright (c) 2018-present Sonatype, Inc.
 * All rights reserved. Includes the third-party code listed at http://links.sonatype.com/products/nexus/oss/attributions.
 *
 * This program and the accompanying materials are made available under the terms of the Eclipse Public License Version 1.0,
 * which accompanies this distribution and is available at http://www.eclipse.org/legal/epl-v10.html.
 *
 * Sonatype Nexus (TM) Professional Version is available from Sonatype, Inc. "Sonatype" and "Sonatype Nexus" are trademarks
 * of Sonatype, Inc. Apache Maven is a trademark of the Apache Software Foundation. M2eclipse is a trademark of the
 * Eclipse Foundation. All other trademarks are the property of their respective owners.
 */
package org.sonatype.nexus.repository.elpa.internal

import javax.annotation.Nonnull
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Provider
import javax.inject.Singleton

import org.sonatype.nexus.repository.Format
import org.sonatype.nexus.repository.RecipeSupport
import org.sonatype.nexus.repository.Repository
import org.sonatype.nexus.repository.attributes.AttributesFacet
import org.sonatype.nexus.repository.cache.NegativeCacheFacet
import org.sonatype.nexus.repository.cache.NegativeCacheHandler
import org.sonatype.nexus.repository.http.HttpHandlers
import org.sonatype.nexus.repository.http.PartialFetchHandler
import org.sonatype.nexus.repository.httpclient.HttpClientFacet
import org.sonatype.nexus.repository.proxy.ProxyHandler
import org.sonatype.nexus.repository.purge.PurgeUnusedFacet
import org.sonatype.nexus.repository.search.SearchFacet
import org.sonatype.nexus.repository.security.SecurityHandler
import org.sonatype.nexus.repository.storage.SingleAssetComponentMaintenance
import org.sonatype.nexus.repository.storage.StorageFacet
import org.sonatype.nexus.repository.storage.UnitOfWorkHandler
import org.sonatype.nexus.repository.types.ProxyType
import org.sonatype.nexus.repository.Type
import org.sonatype.nexus.repository.view.ConfigurableViewFacet
import org.sonatype.nexus.repository.view.Context
import org.sonatype.nexus.repository.view.Route
import org.sonatype.nexus.repository.view.Router
import org.sonatype.nexus.repository.view.ViewFacet
import org.sonatype.nexus.repository.view.handlers.BrowseUnsupportedHandler
import org.sonatype.nexus.repository.view.handlers.ConditionalRequestHandler
import org.sonatype.nexus.repository.view.handlers.ContentHeadersHandler
import org.sonatype.nexus.repository.view.handlers.ExceptionHandler
import org.sonatype.nexus.repository.view.handlers.HandlerContributor
import org.sonatype.nexus.repository.view.handlers.TimingHandler
import org.sonatype.nexus.repository.view.matchers.ActionMatcher
import org.sonatype.nexus.repository.view.matchers.logic.LogicMatchers
import org.sonatype.nexus.repository.view.matchers.token.TokenMatcher

import static org.sonatype.nexus.repository.elpa.internal.AssetKind.*
import static org.sonatype.nexus.repository.http.HttpMethods.GET
import static org.sonatype.nexus.repository.http.HttpMethods.HEAD

/**
 * ELPA proxy repository recipe
 */
@Named(ElpaProxyRecipe.NAME)
@Singleton
class ElpaProxyRecipe
    extends RecipeSupport
{
  public static final String NAME = 'elpa-proxy'

  @Inject
  ExceptionHandler exceptionHandler

  @Inject
  TimingHandler timingHandler

  @Inject
  SecurityHandler securityHandler

  @Inject
  PartialFetchHandler partialFetchHandler

  @Inject
  ConditionalRequestHandler conditionalRequestHandler

  @Inject
  ContentHeadersHandler contentHeadersHandler

  @Inject
  UnitOfWorkHandler unitOfWorkHandler

  @Inject
  ProxyHandler proxyHandler

  @Inject
  NegativeCacheHandler negativeCacheHandler

  @Inject
  HandlerContributor handlerContributor

  @Inject
  Provider<ElpaProxyFacetImpl> proxyFacet

  @Inject
  Provider<ElpaSecurityFacet> securityFacet

  @Inject
  Provider<ConfigurableViewFacet> viewFacet

  @Inject
  Provider<StorageFacet> storageFacet

  @Inject
  Provider<SearchFacet> searchFacet

  @Inject
  Provider<AttributesFacet> attributesFacet

  @Inject
  Provider<SingleAssetComponentMaintenance> componentMaintenanceFacet

  @Inject
  Provider<HttpClientFacet> httpClientFacet

  @Inject
  Provider<NegativeCacheFacet> negativeCacheFacet

  @Inject
  Provider<PurgeUnusedFacet> purgeUnusedFacet

  @Inject
  ElpaProxyRecipe(@Named(ProxyType.NAME) final Type type,
                  @Named(ElpaFormat.NAME) final Format format)
  {
    super(type, format)
  }

  @Override
  void apply(@Nonnull final Repository repository) throws Exception {
    repository.attach(securityFacet.get())
    repository.attach(configure(viewFacet.get()))
    repository.attach(httpClientFacet.get())
    repository.attach(negativeCacheFacet.get())
    repository.attach(componentMaintenanceFacet.get())
    repository.attach(proxyFacet.get())
    repository.attach(storageFacet.get())
    repository.attach(searchFacet.get())
    repository.attach(purgeUnusedFacet.get())
    repository.attach(attributesFacet.get())
  }

  Closure assetKindHandler = { Context context, AssetKind value ->
    context.attributes.set(AssetKind, value)
    return context.proceed()
  }

  /**
   * Matcher for archive contents.
   */
  static Route.Builder archiveContentsMatcher() {
    new Route.Builder().matcher(
        LogicMatchers.and(
            new ActionMatcher(GET, HEAD),
            new TokenMatcher('/{filename:archive-contents}')
        ))
  }

  /**
   * Matcher for archive mapping.
   */
  static Route.Builder archiveMatcher() {
    new Route.Builder().matcher(
        LogicMatchers.and(
            new ActionMatcher(GET, HEAD),
            new TokenMatcher('/{nameVersion:.+}.{extension:tar}')
        ))
  }

  /**
   * Matcher for single file mapping.
   */
  static Route.Builder singleFileMatcher() {
    new Route.Builder().matcher(
        LogicMatchers.and(
            new ActionMatcher(GET, HEAD),
            new TokenMatcher('/{nameVersion:.+}.{extension:el}')
        ))
  }

  /**
   * Matcher for signature file mapping.
   */
  static Route.Builder signatureFileMatcher() {
    new Route.Builder().matcher(
        LogicMatchers.and(
            new ActionMatcher(GET, HEAD),
            LogicMatchers.or(
                new TokenMatcher('/{nameVersion:.+}.{extension:el}.sig'),
                new TokenMatcher('/{nameVersion:.+}.{extension:tar}.sig')
            )
        )
    )
  }

  /**
   * Configure {@link ViewFacet}.
   */
  private ViewFacet configure(final ConfigurableViewFacet facet) {
    Router.Builder builder = new Router.Builder()

    builder.route(archiveMatcher()
        .handler(timingHandler)
        .handler(assetKindHandler.rcurry(ARCHIVE))
        .handler(securityHandler)
        .handler(exceptionHandler)
        .handler(handlerContributor)
        .handler(negativeCacheHandler)
        .handler(conditionalRequestHandler)
        .handler(partialFetchHandler)
        .handler(contentHeadersHandler)
        .handler(unitOfWorkHandler)
        .handler(proxyHandler)
        .create())

    builder.route(singleFileMatcher()
        .handler(timingHandler)
        .handler(assetKindHandler.rcurry(SINGLE))
        .handler(securityHandler)
        .handler(exceptionHandler)
        .handler(handlerContributor)
        .handler(negativeCacheHandler)
        .handler(conditionalRequestHandler)
        .handler(partialFetchHandler)
        .handler(contentHeadersHandler)
        .handler(unitOfWorkHandler)
        .handler(proxyHandler)
        .create())

    builder.route(signatureFileMatcher()
        .handler(timingHandler)
        .handler(assetKindHandler.rcurry(SIGNATURE))
        .handler(securityHandler)
        .handler(exceptionHandler)
        .handler(handlerContributor)
        .handler(negativeCacheHandler)
        .handler(conditionalRequestHandler)
        .handler(partialFetchHandler)
        .handler(contentHeadersHandler)
        .handler(unitOfWorkHandler)
        .handler(proxyHandler)
        .create())

    builder.route(archiveContentsMatcher()
        .handler(timingHandler)
        .handler(assetKindHandler.rcurry(CONTENTS))
        .handler(securityHandler)
        .handler(exceptionHandler)
        .handler(handlerContributor)
        .handler(negativeCacheHandler)
        .handler(conditionalRequestHandler)
        .handler(partialFetchHandler)
        .handler(contentHeadersHandler)
        .handler(unitOfWorkHandler)
        .handler(proxyHandler)
        .create())

    builder.defaultHandlers(HttpHandlers.notFound())
    facet.configure(builder.create())
    return facet
  }
}

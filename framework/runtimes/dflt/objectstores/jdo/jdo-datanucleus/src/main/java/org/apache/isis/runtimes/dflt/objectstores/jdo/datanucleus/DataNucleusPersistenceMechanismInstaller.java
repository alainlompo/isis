package org.apache.isis.runtimes.dflt.objectstores.jdo.datanucleus;


import java.util.List;
import java.util.Map;

import javax.annotation.Nullable;

import org.apache.isis.core.commons.components.Installer;
import org.apache.isis.core.commons.config.IsisConfiguration;
import org.apache.isis.core.metamodel.adapter.ObjectAdapterFactory;
import org.apache.isis.core.metamodel.progmodel.ProgrammingModel;
import org.apache.isis.core.metamodel.spec.SpecificationLoaderSpi;
import org.apache.isis.core.metamodel.specloader.classsubstitutor.ClassSubstitutor;
import org.apache.isis.core.metamodel.specloader.validator.MetaModelValidator;
import org.apache.isis.core.metamodel.specloader.validator.MetaModelValidatorComposite;
import org.apache.isis.core.progmodel.facets.object.ignore.jdo.RemoveJdoEnhancementTypesFacetFactory;
import org.apache.isis.core.progmodel.facets.object.ignore.jdo.RemoveJdoPrefixedMethodsFacetFactory;
import org.apache.isis.runtimes.dflt.bytecode.identity.objectfactory.ObjectFactoryBasic;
import org.apache.isis.runtimes.dflt.objectstores.jdo.applib.AuditService;
import org.apache.isis.runtimes.dflt.objectstores.jdo.datanucleus.bytecode.DataNucleusTypesClassSubstitutor;
import org.apache.isis.runtimes.dflt.objectstores.jdo.datanucleus.metamodel.specloader.progmodelfacets.DataNucleusProgrammingModelFacets;
import org.apache.isis.runtimes.dflt.objectstores.jdo.datanucleus.persistence.adaptermanager.DataNucleusPojoRecreator;
import org.apache.isis.runtimes.dflt.objectstores.jdo.datanucleus.persistence.spi.DataNucleusIdentifierGenerator;
import org.apache.isis.runtimes.dflt.objectstores.jdo.datanucleus.persistence.spi.DataNucleusSimplePersistAlgorithm;
import org.apache.isis.runtimes.dflt.objectstores.jdo.datanucleus.persistence.spi.DataNucleusTransactionManager;
import org.apache.isis.runtimes.dflt.objectstores.jdo.metamodel.facets.object.auditable.AuditableAnnotationFacetFactory;
import org.apache.isis.runtimes.dflt.objectstores.jdo.metamodel.facets.object.auditable.AuditableMarkerInterfaceFacetFactory;
import org.apache.isis.runtimes.dflt.objectstores.jdo.metamodel.facets.object.datastoreidentity.JdoDatastoreIdentityAnnotationFacetFactory;
import org.apache.isis.runtimes.dflt.objectstores.jdo.metamodel.facets.object.discriminator.JdoDiscriminatorAnnotationFacetFactory;
import org.apache.isis.runtimes.dflt.objectstores.jdo.metamodel.facets.object.embeddedonly.JdoEmbeddedOnlyAnnotationFacetFactory;
import org.apache.isis.runtimes.dflt.objectstores.jdo.metamodel.facets.object.persistencecapable.JdoPersistenceCapableAnnotationFacetFactory;
import org.apache.isis.runtimes.dflt.objectstores.jdo.metamodel.facets.object.query.JdoQueryAnnotationFacetFactory;
import org.apache.isis.runtimes.dflt.objectstores.jdo.metamodel.facets.prop.primarykey.JdoPrimaryKeyAnnotationFacetFactory;
import org.apache.isis.runtimes.dflt.objectstores.jdo.metamodel.specloader.validator.JdoMetaModelValidatorLeaf;
import org.apache.isis.runtimes.dflt.runtime.installerregistry.installerapi.PersistenceMechanismInstallerAbstract;
import org.apache.isis.runtimes.dflt.runtime.persistence.objectstore.ObjectStoreSpi;
import org.apache.isis.runtimes.dflt.runtime.persistence.objectstore.algorithm.PersistAlgorithm;
import org.apache.isis.runtimes.dflt.runtime.persistence.objectstore.transaction.TransactionalResource;
import org.apache.isis.runtimes.dflt.runtime.system.context.IsisContext;
import org.apache.isis.runtimes.dflt.runtime.system.persistence.AdapterManagerSpi;
import org.apache.isis.runtimes.dflt.runtime.system.persistence.IdentifierGenerator;
import org.apache.isis.runtimes.dflt.runtime.system.persistence.ObjectFactory;
import org.apache.isis.runtimes.dflt.runtime.system.persistence.PersistenceSession;
import org.apache.isis.runtimes.dflt.runtime.system.persistence.PersistenceSessionFactory;
import org.apache.isis.runtimes.dflt.runtime.system.transaction.EnlistedObjectDirtying;
import org.apache.isis.runtimes.dflt.runtime.system.transaction.IsisTransactionManager;

import com.google.common.base.Optional;
import com.google.common.base.Predicate;
import com.google.common.collect.Iterables;

/**
 * Configuration files are read in the usual fashion (as per {@link Installer#getConfigurationResources()}, ie will consult all of:
 * <ul>
 * <li><tt>persistor_datanucleus.properties</tt>
 * <li><tt>persistor.properties</tt>
 * <li><tt>isis.properties</tt>
 * </ul>
 * 
 * <p>
 * With respect to configuration, all properties under {@value #ISIS_CONFIG_PREFIX} prefix are passed 
 * through verbatim to the DataNucleus runtime. For example:
 * <table>
 * <tr><th>Isis Property</th><th>DataNucleus Property</th></tr>
 * <tr><td><tt>isis.persistor.datanucleus.impl.datanucleus.foo.Bar</tt></td><td><tt>datanucleus.foo.Bar</tt></td></tr>
 * </table>
 *
 */
public class DataNucleusPersistenceMechanismInstaller extends PersistenceMechanismInstallerAbstract {

    private static final Predicate<Object> LOCATE_AUDIT_SERVICE = new Predicate<Object>() {

        @Override
        public boolean apply(Object input) {
            return input instanceof AuditService;
        }
    };

    public static final String NAME = "datanucleus";
    private static final String ISIS_CONFIG_PREFIX = "isis.persistor.datanucleus.impl";

    private DataNucleusApplicationComponents applicationComponents = null;

    // only search once
    private boolean searchedForAuditService;
    @Nullable
    private AuditService auditService;
    
    public DataNucleusPersistenceMechanismInstaller() {
        super(NAME);
    }

    
    ////////////////////////////////////////////////////////////////////////
    // createObjectStore
    ////////////////////////////////////////////////////////////////////////
    
    @Override
    protected ObjectStoreSpi createObjectStore(IsisConfiguration configuration, ObjectAdapterFactory adapterFactory, AdapterManagerSpi adapterManager) {
        createDataNucleusApplicationComponentsIfRequired(configuration);
        return new DataNucleusObjectStore(adapterFactory, applicationComponents);
    }

    private void createDataNucleusApplicationComponentsIfRequired(IsisConfiguration configuration) {
        if(applicationComponents != null) {
            return;
        }
        
        final IsisConfiguration dataNucleusConfig = configuration.createSubset(ISIS_CONFIG_PREFIX);
        final Map<String, String> props = dataNucleusConfig.asMap();
        
        applicationComponents = new DataNucleusApplicationComponents(props, getSpecificationLoader().allSpecifications());
    }

    ////////////////////////////////////////////////////////////////////////
    // createPersistenceSession
    ////////////////////////////////////////////////////////////////////////

    @Override
    public PersistenceSession createPersistenceSession(PersistenceSessionFactory persistenceSessionFactory) {
        PersistenceSession persistenceSession = super.createPersistenceSession(persistenceSessionFactory);
        searchAndCacheAuditServiceIfNotAlreadyDoneSo(persistenceSessionFactory);
        return persistenceSession;
    }

    private void searchAndCacheAuditServiceIfNotAlreadyDoneSo(PersistenceSessionFactory persistenceSessionFactory) {
        if(searchedForAuditService) {
            return;
        } 
        List<Object> services = persistenceSessionFactory.getServices();
        final Optional<Object> optionalService = Iterables.tryFind(services, LOCATE_AUDIT_SERVICE);
        if(optionalService.isPresent()) {
            auditService = (AuditService) optionalService.get();
        }
        searchedForAuditService = true;
    }

    ////////////////////////////////////////////////////////////////////////
    // PersistenceSessionFactoryDelegate impl
    ////////////////////////////////////////////////////////////////////////

    @Override
    protected PersistAlgorithm createPersistAlgorithm(IsisConfiguration configuration) {
        return new DataNucleusSimplePersistAlgorithm();
    }
    
    @Override
    public IdentifierGenerator createIdentifierGenerator(IsisConfiguration configuration) {
        return new DataNucleusIdentifierGenerator();
    }

    @Override
    public ClassSubstitutor createClassSubstitutor(IsisConfiguration configuration) {
        return new DataNucleusTypesClassSubstitutor();
    }

    @Override
    public ProgrammingModel refineProgrammingModel(ProgrammingModel baseProgrammingModel, IsisConfiguration configuration) {

        addJdoFacetFactories(baseProgrammingModel);
        addDataNucleusFacetFactories(baseProgrammingModel);

        return baseProgrammingModel;
    }

    private void addJdoFacetFactories(ProgrammingModel baseProgrammingModel) {
        baseProgrammingModel.addFactory(JdoPersistenceCapableAnnotationFacetFactory.class);
        baseProgrammingModel.addFactory(JdoDatastoreIdentityAnnotationFacetFactory.class);
        baseProgrammingModel.addFactory(JdoEmbeddedOnlyAnnotationFacetFactory.class);

        baseProgrammingModel.addFactory(JdoPrimaryKeyAnnotationFacetFactory.class);
        baseProgrammingModel.addFactory(JdoDiscriminatorAnnotationFacetFactory.class);

        baseProgrammingModel.addFactory(JdoQueryAnnotationFacetFactory.class);
        
        baseProgrammingModel.addFactory(AuditableAnnotationFacetFactory.class);
        baseProgrammingModel.addFactory(AuditableMarkerInterfaceFacetFactory.class);
    }

    private void addDataNucleusFacetFactories(ProgrammingModel baseProgrammingModel) {
        baseProgrammingModel.addFactory(RemoveJdoEnhancementTypesFacetFactory.class);
        baseProgrammingModel.addFactory(RemoveJdoPrefixedMethodsFacetFactory.class);
    }

    @Override
    public MetaModelValidator refineMetaModelValidator(MetaModelValidatorComposite baseMetaModelValidator, IsisConfiguration configuration) {
        return baseMetaModelValidator.add(new JdoMetaModelValidatorLeaf());
    }


    @Override
    protected IsisTransactionManager createTransactionManager(final EnlistedObjectDirtying persistor, final TransactionalResource objectStore) {
        return new DataNucleusTransactionManager(persistor, objectStore, auditService);
    }

    @Override
    public ObjectFactory createObjectFactory(IsisConfiguration configuration) {
        return new ObjectFactoryBasic();
    }

    @Override
    public DataNucleusPojoRecreator createPojoRecreator(IsisConfiguration configuration) {
        return new DataNucleusPojoRecreator();
    }




    
    ////////////////////////////////////////////////////////////////////////
    // Dependencies
    ////////////////////////////////////////////////////////////////////////
    
    protected SpecificationLoaderSpi getSpecificationLoader() {
        return IsisContext.getSpecificationLoader();
    }




}

package org.molgenis.data.meta;

import static com.google.common.collect.Lists.newArrayList;
import static com.google.common.collect.Lists.reverse;
import static org.molgenis.security.core.utils.SecurityUtils.getCurrentUsername;
import static org.molgenis.util.SecurityDecoratorUtils.validatePermission;

import java.util.Collection;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

import org.molgenis.MolgenisFieldTypes;
import org.molgenis.data.AttributeMetaData;
import org.molgenis.data.Entity;
import org.molgenis.data.EntityMetaData;
import org.molgenis.data.ManageableRepositoryCollection;
import org.molgenis.data.MolgenisDataException;
import org.molgenis.data.Package;
import org.molgenis.data.Repository;
import org.molgenis.data.RepositoryCollection;
import org.molgenis.data.RepositoryDecoratorFactory;
import org.molgenis.data.UnknownEntityException;
import org.molgenis.data.i18n.I18nStringDecorator;
import org.molgenis.data.i18n.I18nStringMetaData;
import org.molgenis.data.i18n.LanguageMetaData;
import org.molgenis.data.i18n.LanguageRepositoryDecorator;
import org.molgenis.data.i18n.LanguageService;
import org.molgenis.data.meta.system.ImportRunMetaData;
import org.molgenis.data.support.DataServiceImpl;
import org.molgenis.data.support.DefaultAttributeMetaData;
import org.molgenis.data.support.DefaultEntityMetaData;
import org.molgenis.data.support.NonDecoratingRepositoryDecoratorFactory;
import org.molgenis.security.core.Permission;
import org.molgenis.security.core.runas.RunAsSystem;
import org.molgenis.security.core.runas.RunAsSystemProxy;
import org.molgenis.security.core.utils.SecurityUtils;
import org.molgenis.util.DependencyResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.event.ContextRefreshedEvent;
import org.springframework.core.Ordered;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;

/**
 * MetaData service. Administration of the {@link Package}, {@link EntityMetaData} and {@link AttributeMetaData} of the
 * metadata of the repositories.
 * 
 * TODO: This class smells. It started out as a simple administration but taken on a new role: to bootstrap the
 * repositories and orchestrate changes in metadata. There's a second, higher level, class in here that needs to be
 * refactored out. See also {@link MetaValidationUtils} which does some of this work now already.
 * 
 * <img src="http://yuml.me/041e5382.png" alt="Metadata entities" width="640"/>
 */
public class MetaDataServiceImpl implements MetaDataService
{
	private static final Logger LOG = LoggerFactory.getLogger(MetaDataServiceImpl.class);

	private PackageRepository packageRepository;
	private EntityMetaDataRepository entityMetaDataRepository;
	private AttributeMetaDataRepository attributeMetaDataRepository;
	private ManageableRepositoryCollection defaultBackend;
	private final Map<String, RepositoryCollection> backends = Maps.newHashMap();
	private final DataServiceImpl dataService;
	private TransactionTemplate transactionTemplate;
	private LanguageService languageService;

	public MetaDataServiceImpl(DataServiceImpl dataService)
	{
		this.dataService = dataService;
	}

	@Autowired
	public void setLanguageService(LanguageService languageService)
	{
		this.languageService = languageService;
	}

	@Autowired
	public void setPlatformTransactionManager(PlatformTransactionManager transactionManager)
	{
		this.transactionTemplate = new TransactionTemplate(transactionManager);
	}

	/**
	 * Sets the Backend, in wich the meta data and the user data is saved
	 * 
	 * Setter for the ManageableCrudRepositoryCollection, to be called after it's created. This resolves the circular
	 * dependency {@link MysqlRepositoryCollection} => decorated {@link MetaDataService} => {@link RepositoryCreator}
	 * 
	 * @param ManageableRepositoryCollection
	 */
	@Override
	public MetaDataService setDefaultBackend(ManageableRepositoryCollection backend)
	{
		this.defaultBackend = backend;
		backends.put(backend.getName(), backend);

		I18nStringMetaData.INSTANCE.setBackend(backend.getName());
		LanguageMetaData.INSTANCE.setBackend(backend.getName());
		PackageMetaData.INSTANCE.setBackend(backend.getName());
		TagMetaData.INSTANCE.setBackend(backend.getName());
		EntityMetaDataMetaData.INSTANCE.setBackend(backend.getName());
		AttributeMetaDataMetaData.INSTANCE.setBackend(backend.getName());

		ImportRunMetaData.INSTANCE.setBackend(backend.getName());

		bootstrapMetaRepos();
		return this;
	}

	private void bootstrapMetaRepos()
	{
		Repository languageRepo = defaultBackend.addEntityMeta(LanguageMetaData.INSTANCE);
		dataService.addRepository(new LanguageRepositoryDecorator(languageRepo, dataService));

		Repository i18StringsRepo = defaultBackend.addEntityMeta(I18nStringMetaData.INSTANCE);
		dataService.addRepository(new I18nStringDecorator(i18StringsRepo));

		Supplier<Stream<String>> languageCodes = () -> languageService.getLanguageCodes().stream();

		// Add language attributes to the AttributeMetaDataMetaData
		languageCodes.get().map(code -> AttributeMetaDataMetaData.LABEL + '-' + code)
				.forEach(AttributeMetaDataMetaData.INSTANCE::addAttribute);

		// Add description attributes to the AttributeMetaDataMetaData
		languageCodes.get().map(code -> AttributeMetaDataMetaData.DESCRIPTION + '-' + code)
				.forEach(attrName -> AttributeMetaDataMetaData.INSTANCE.addAttribute(attrName)
						.setDataType(MolgenisFieldTypes.TEXT));

		// Add description attributes to the EntityMetaDataMetaData
		languageCodes.get().map(code -> EntityMetaDataMetaData.DESCRIPTION + '-' + code)
				.forEach(attrName -> EntityMetaDataMetaData.INSTANCE.addAttribute(attrName)
						.setDataType(MolgenisFieldTypes.TEXT));

		// Add language attributes to the EntityMetaDataMetaData
		languageCodes.get().map(code -> EntityMetaDataMetaData.LABEL + '-' + code)
				.forEach(EntityMetaDataMetaData.INSTANCE::addAttribute);

		// Add language attributes to I18nStringMetaData
		languageCodes.get().forEach(I18nStringMetaData.INSTANCE::addLanguage);

		Repository tagRepo = defaultBackend.addEntityMeta(TagMetaData.INSTANCE);
		dataService.addRepository(tagRepo);

		Repository packages = defaultBackend.addEntityMeta(PackageRepository.META_DATA);
		dataService.addRepository(new MetaDataRepositoryDecorator(packages));
		packageRepository = new PackageRepository(packages);

		attributeMetaDataRepository = new AttributeMetaDataRepository(defaultBackend, languageService);
		entityMetaDataRepository = new EntityMetaDataRepository(defaultBackend, packageRepository,
				attributeMetaDataRepository, languageService);
		attributeMetaDataRepository.setEntityMetaDataRepository(entityMetaDataRepository);

		dataService.addRepository(new MetaDataRepositoryDecorator(attributeMetaDataRepository.getRepository()));
		dataService.addRepository(new MetaDataRepositoryDecorator(entityMetaDataRepository.getRepository()));
		entityMetaDataRepository.fillEntityMetaDataCache();
	}

	@Override
	public ManageableRepositoryCollection getDefaultBackend()
	{
		return defaultBackend;
	}

	@Override
	public RepositoryCollection getBackend(String name)
	{
		return backends.get(name);
	}

	/**
	 * Removes entity meta data if it exists.
	 */
	@Override
	public void deleteEntityMeta(String entityName)
	{
		validatePermission(entityName, Permission.WRITEMETA);

		transactionTemplate.execute((TransactionStatus status) -> {
			EntityMetaData emd = getEntityMetaData(entityName);
			if ((emd != null) && !emd.isAbstract())
			{
				getManageableRepositoryCollection(emd).deleteEntityMeta(entityName);
			}
			entityMetaDataRepository.delete(entityName);
			if (dataService.hasRepository(entityName)) dataService.removeRepository(entityName);
			deleteEntityPermissions(entityName);

			return null;
		});

		refreshCaches();
		LOG.info("Repository [{}] deleted by user [{}]", entityName, getCurrentUsername());
	}

	private void deleteEntityPermissions(String entityName)
	{
		List<String> authorities = SecurityUtils.getEntityAuthorities(entityName);

		// User permissions
		if (dataService.hasRepository("UserAuthority"))
		{
			Stream<Entity> userPermissions = dataService.query("UserAuthority").in("role", authorities).findAll();
			dataService.delete("UserAuthority", userPermissions);
		}

		// Group permissions
		if (dataService.hasRepository("GroupAuthority"))
		{
			Stream<Entity> groupPermissions = dataService.query("GroupAuthority").in("role", authorities).findAll();
			dataService.delete("GroupAuthority", groupPermissions);
		}
	}

	@Transactional
	@Override
	public void delete(List<EntityMetaData> entities)
	{
		entities.forEach(emd -> validatePermission(emd.getName(), Permission.WRITEMETA));

		reverse(DependencyResolver.resolve(Sets.newHashSet(entities))).stream().map(EntityMetaData::getName)
				.forEach(this::deleteEntityMeta);
	}

	/**
	 * Removes an attribute from an entity.
	 */
	@Transactional
	@Override
	public void deleteAttribute(String entityName, String attributeName)
	{
		validatePermission(entityName, Permission.WRITEMETA);

		// Update AttributeMetaDataRepository
		entityMetaDataRepository.removeAttribute(entityName, attributeName);
		EntityMetaData emd = getEntityMetaData(entityName);
		if (emd != null) getManageableRepositoryCollection(emd).deleteAttribute(entityName, attributeName);
	}

	private ManageableRepositoryCollection getManageableRepositoryCollection(EntityMetaData emd)
	{
		RepositoryCollection backend = getBackend(emd);
		if (!(backend instanceof ManageableRepositoryCollection))
			throw new RuntimeException("Backend  is not a ManageableCrudRepositoryCollection");

		return (ManageableRepositoryCollection) backend;
	}

	@Override
	public RepositoryCollection getBackend(EntityMetaData emd)
	{
		String backendName = emd.getBackend() == null ? getDefaultBackend().getName() : emd.getBackend();
		RepositoryCollection backend = backends.get(backendName);
		if (backend == null) throw new RuntimeException("Unknown backend [" + backendName + "]");

		return backend;
	}

	@Transactional
	@Override
	public synchronized Repository add(EntityMetaData emd, RepositoryDecoratorFactory decoratorFactory)
	{
		MetaValidationUtils.validateEntityMetaData(emd);
		RepositoryCollection backend = getBackend(emd);

		if (getEntityMetaData(emd.getName()) != null)
		{
			if (emd.isAbstract()) return null;

			if (!dataService.hasRepository(emd.getName()))
			{
				Repository repo = backend.getRepository(emd.getName());
				if (repo == null) throw new UnknownEntityException(
						String.format("Unknown entity '%s' for backend '%s'", emd.getName(), backend.getName()));
				Repository decoratedRepo = decoratorFactory.createDecoratedRepository(repo);
				dataService.addRepository(decoratedRepo);
			}

			// Return decorated repo
			return dataService.getRepository(emd.getName());
		}

		if (dataService.hasRepository(emd.getName()))
		{
			throw new MolgenisDataException("Entity with name [" + emd.getName() + "] already exists.");
		}

		if (emd.getPackage() != null)
		{
			packageRepository.add(emd.getPackage());
		}

		addToEntityMetaDataRepository(emd);
		if (emd.isAbstract()) return null;

		Repository repo = backend.addEntityMeta(getEntityMetaData(emd.getName()));
		Repository decoratedRepo = decoratorFactory.createDecoratedRepository(repo);

		dataService.addRepository(decoratedRepo);

		// Return decorated repo
		return dataService.getRepository(emd.getName());
	}

	@Transactional
	@Override
	public Repository addEntityMeta(EntityMetaData emd)
	{
		return add(emd, new NonDecoratingRepositoryDecoratorFactory());
	}

	@Transactional
	@Override
	public void addAttribute(String fullyQualifiedEntityName, AttributeMetaData attr)
	{
		validatePermission(fullyQualifiedEntityName, Permission.WRITEMETA);
		MetaValidationUtils.validateName(attr.getName());

		EntityMetaData emd = entityMetaDataRepository.addAttribute(fullyQualifiedEntityName, attr);
		getManageableRepositoryCollection(emd).addAttribute(fullyQualifiedEntityName, attr);
	}

	@Override
	public void addAttributeSync(String fullyQualifiedEntityName, AttributeMetaData attr)
	{
		validatePermission(fullyQualifiedEntityName, Permission.WRITEMETA);
		MetaValidationUtils.validateName(attr.getName());

		EntityMetaData emd = entityMetaDataRepository.addAttribute(fullyQualifiedEntityName, attr);
		getManageableRepositoryCollection(emd).addAttributeSync(fullyQualifiedEntityName, attr);
	}

	@Override
	public DefaultEntityMetaData getEntityMetaData(String fullyQualifiedEntityName)
	{
		// at construction time, will be called when entityMetaDataRepository is still null
		if (attributeMetaDataRepository == null)
		{
			return null;
		}
		return entityMetaDataRepository.get(fullyQualifiedEntityName);
	}

	@Override
	public void addPackage(Package p)
	{
		MetaValidationUtils.validateName(p.getName());
		packageRepository.add(p);
	}

	@Override
	public Package getPackage(String string)
	{
		return packageRepository.getPackage(string);
	}

	@Override
	public List<Package> getPackages()
	{
		return packageRepository.getPackages();
	}

	@Override
	public List<Package> getRootPackages()
	{
		return packageRepository.getRootPackages();
	}

	/**
	 * Empties all metadata tables for the sake of testability.
	 */
	@Transactional
	public void recreateMetaDataRepositories()
	{
		delete(newArrayList(getEntityMetaDatas()));

		attributeMetaDataRepository.deleteAll();
		entityMetaDataRepository.deleteAll();
		packageRepository.deleteAll();
		packageRepository.updatePackageCache();
	}

	@Override
	public Collection<EntityMetaData> getEntityMetaDatas()
	{
		return entityMetaDataRepository.getMetaDatas();
	}

	// TODO make private
	@Override
	public void refreshCaches()
	{
		RunAsSystemProxy.runAsSystem(() -> {
			packageRepository.updatePackageCache();
			entityMetaDataRepository.fillEntityMetaDataCache();
			return null;
		});
	}

	@Transactional
	@Override
	public List<AttributeMetaData> updateEntityMeta(EntityMetaData entityMeta)
	{
		return MetaUtils.updateEntityMeta(this, entityMeta, false);
	}

	@Override
	@Transactional
	public List<AttributeMetaData> updateSync(EntityMetaData sourceEntityMetaData)
	{

		return MetaUtils.updateEntityMeta(this, sourceEntityMetaData, true);
	}

	@Override
	public int getOrder()
	{
		return Ordered.HIGHEST_PRECEDENCE;
	}

	public void addBackend(RepositoryCollection backend)
	{
		backends.put(backend.getName(), backend);
	}

	@Override
	@RunAsSystem
	public synchronized void onApplicationEvent(ContextRefreshedEvent event)
	{
		// Discover all backends
		Map<String, RepositoryCollection> backendBeans = event.getApplicationContext()
				.getBeansOfType(RepositoryCollection.class);
		backendBeans.values().forEach(this::addBackend);

		Map<String, EntityMetaData> emds = event.getApplicationContext().getBeansOfType(EntityMetaData.class);

		// Create repositories from EntityMetaData in EntityMetaData repo
		for (EntityMetaData emd : entityMetaDataRepository.getMetaDatas())
		{
			if (!emd.isAbstract() && !dataService.hasRepository(emd.getName()))
			{
				RepositoryCollection col = backends.get(emd.getBackend());
				if (col == null) throw new MolgenisDataException("Unknown backend [" + emd.getBackend() + "]");
				Repository repo = col.addEntityMeta(emd);
				dataService.addRepository(repo);
			}
		}

		// Discover static EntityMetaData
		DependencyResolver.resolve(Sets.newHashSet(emds.values())).stream()
				.filter(emd -> !dataService.hasRepository(emd.getName())).forEach(this::addEntityMeta);

		// Update update manageable backends
		DependencyResolver.resolve(Sets.newHashSet(emds.values())).stream().filter(this::isManageableBackend)
				.forEach(this::updateEntityMeta);
	}

	private boolean isManageableBackend(EntityMetaData emd)
	{
		// Might work for more than just MySQL backend
		return emd.getBackend() == null || "MySql".equals(emd.getBackend());
	}

	@Override
	public Iterator<RepositoryCollection> iterator()
	{
		return backends.values().iterator();
	}

	public void updateEntityMetaBackend(String entityName, String backend)
	{
		validatePermission(entityName, Permission.WRITEMETA);

		DefaultEntityMetaData entityMeta = entityMetaDataRepository.get(entityName);
		if (entityMeta == null) throw new UnknownEntityException("Unknown entity '" + entityName + "'");
		entityMeta.setBackend(backend);
		entityMetaDataRepository.update(entityMeta);
	}

	public void addToEntityMetaDataRepository(EntityMetaData entityMetaData)
	{
		MetaValidationUtils.validateEntityMetaData(entityMetaData);
		entityMetaDataRepository.add(entityMetaData);
	}

	@Override
	public LinkedHashMap<String, Boolean> integrationTestMetaData(RepositoryCollection repositoryCollection)
	{
		LinkedHashMap<String, Boolean> entitiesImportable = new LinkedHashMap<String, Boolean>();
		StreamSupport.stream(repositoryCollection.getEntityNames().spliterator(), false)
				.forEach(entityName -> entitiesImportable.put(entityName, this.canIntegrateEntityMetadataCheck(
						repositoryCollection.getRepository(entityName).getEntityMetaData())));

		return entitiesImportable;
	}

	@Override
	public LinkedHashMap<String, Boolean> integrationTestMetaData(
			ImmutableMap<String, EntityMetaData> newEntitiesMetaDataMap, List<String> skipEntities,
			String defaultPackage)
	{
		LinkedHashMap<String, Boolean> entitiesImportable = new LinkedHashMap<String, Boolean>();

		StreamSupport.stream(newEntitiesMetaDataMap.keySet().spliterator(), false)
				.forEach(entityName -> entitiesImportable.put(entityName, skipEntities.contains(entityName)
						|| this.canIntegrateEntityMetadataCheck(newEntitiesMetaDataMap.get(entityName))));

		return entitiesImportable;
	}

	public boolean canIntegrateEntityMetadataCheck(EntityMetaData newEntityMetaData)
	{
		String entityName = newEntityMetaData.getName();
		if (dataService.hasRepository(entityName))
		{
			EntityMetaData newEntity = newEntityMetaData;
			EntityMetaData oldEntity = dataService.getEntityMetaData(entityName);

			List<AttributeMetaData> oldAtomicAttributes = StreamSupport
					.stream(oldEntity.getAtomicAttributes().spliterator(), false)
					.collect(Collectors.<AttributeMetaData> toList());

			LinkedHashMap<String, AttributeMetaData> newAtomicAttributesMap = new LinkedHashMap<String, AttributeMetaData>();
			StreamSupport.stream(newEntity.getAtomicAttributes().spliterator(), false)
					.forEach(attribute -> newAtomicAttributesMap.put(attribute.getName(), attribute));

			for (AttributeMetaData oldAttribute : oldAtomicAttributes)
			{
				if (!newAtomicAttributesMap.keySet().contains(oldAttribute.getName())) return false;

				DefaultAttributeMetaData oldAttributDefault = new DefaultAttributeMetaData(oldAttribute);
				DefaultAttributeMetaData newAttributDefault = new DefaultAttributeMetaData(
						newAtomicAttributesMap.get(oldAttribute.getName()));

				if (!oldAttributDefault.isSameAs(newAttributDefault)) return false;
			}
		}

		return true;
	}

	@Override
	public boolean hasBackend(String backendName)
	{
		return backends.containsKey(backendName);
	}
}